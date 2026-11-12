package com.javadropbox.javadropbox.config;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Slows down guessing: after {@link #MAX_FAILURES} failed attempts from one address within {@link
 * #WINDOW}, that address is refused for {@link #LOCKOUT}. Used for sign-ins and, separately, for
 * setup codes, each a {@code kind} of its own.
 *
 * <p>The counts are kept in the database's {@code login_attempts} table, not in memory, so that
 * every server shares them: behind a load balancer, two servers allow no more guesses than one.
 * Each call reads and writes the address's row in a transaction of its own, holding its lock, so
 * that calls on different servers at the same moment don't lose each other's counts. An address
 * already locked out is answered from a plain read. Rows whose failures can no longer lead to or
 * extend a lockout are deleted, at most once a minute on each server. The servers' clocks have to
 * agree, as they do for sessions.
 *
 * <p>Each attempt is reserved with {@link #tryAcquire} before the password is checked and then
 * ended with {@link #recordFailure}, {@link #recordSuccess} or {@link #release}. Attempts still in
 * progress count towards the limit, so a burst of parallel requests gets no more password checks
 * than sequential ones would. A server that stops in the middle of a check never ends its
 * reservation, so reservations stop counting {@link #RESERVATION} after the last one was made.
 */
public class LoginAttemptLimiter {

  /** What sign-ins are counted as. */
  public static final String SIGN_IN = "sign-in";

  /** What setup codes are counted as. */
  public static final String SETUP_CODE = "setup-code";

  static final int MAX_FAILURES = 5;
  static final Duration WINDOW = Duration.ofMinutes(15);
  static final Duration LOCKOUT = Duration.ofMinutes(15);
  // Far longer than checking a password takes.
  static final Duration RESERVATION = Duration.ofMinutes(1);

  // What a client is told to wait when its remaining attempts are all in progress: they finish
  // within a second or so, after which it is either locked out or may try again.
  private static final Duration BUSY_RETRY = Duration.ofSeconds(1);

  // Once this long has passed since a client's last update, both its window and any lockout its
  // failures started are over, so the row no longer matters.
  private static final Duration RETENTION = WINDOW.compareTo(LOCKOUT) > 0 ? WINDOW : LOCKOUT;
  private static final Duration FORGET_EVERY = Duration.ofMinutes(1);

  private record Attempts(
      int failures,
      Instant firstFailure,
      Instant lockedUntil,
      int inProgress,
      Instant reservedAt,
      Instant updated) {

    boolean lockedAt(Instant now) {
      return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /**
     * These attempts as they stand at {@code now}: failures from an ended window don't count, nor
     * do reservations nobody has ended in a long while.
     */
    Attempts at(Instant now) {
      boolean over =
          lockedUntil != null
              ? !lockedUntil.isAfter(now)
              : firstFailure != null && firstFailure.plus(WINDOW).isBefore(now);
      boolean abandoned = reservedAt != null && reservedAt.plus(RESERVATION).isBefore(now);
      int pending = abandoned ? 0 : inProgress;
      return over
          ? new Attempts(0, null, null, pending, reservedAt, updated)
          : new Attempts(failures, firstFailure, lockedUntil, pending, reservedAt, updated);
    }
  }

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final String kind;
  // When this server last deleted the rows that no longer matter.
  private final AtomicReference<Instant> lastForgotten = new AtomicReference<>();

  /**
   * @param kind what is being guessed, such as {@link #SIGN_IN}: each kind is counted on its own
   */
  public LoginAttemptLimiter(
      JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock, String kind) {
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactions);
    // Its own, committed at once: a failure counts even if a caller's transaction rolls back.
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
    this.kind = kind;
  }

  /**
   * Reserves an attempt for the client if it may make one now. Returns {@link Duration#ZERO} when
   * the attempt is reserved, which the caller must then end with {@link #recordFailure}, {@link
   * #recordSuccess} or {@link #release}; otherwise how long the client should wait.
   */
  public Duration tryAcquire(String client) {
    Instant now = clock.instant();
    forgetExpired(now);
    // A client locked out is answered from a plain read, without writing or waiting for its row's
    // lock: one that keeps trying mustn't tie up the database's connections.
    Duration locked = retryAfter(client);
    if (!locked.isZero()) {
      return locked;
    }
    return transaction.execute(
        status -> {
          Attempts attempts = lockCreating(client, now).at(now);
          if (attempts.lockedAt(now)) {
            return Duration.between(now, attempts.lockedUntil());
          }
          if (attempts.failures() + attempts.inProgress() >= MAX_FAILURES) {
            return BUSY_RETRY;
          }
          store(
              client,
              new Attempts(
                  attempts.failures(),
                  attempts.firstFailure(),
                  null,
                  attempts.inProgress() + 1,
                  now,
                  now));
          return Duration.ZERO;
        });
  }

  /** How long the client must still wait, or {@link Duration#ZERO} if it is not locked out. */
  public Duration retryAfter(String client) {
    Instant now = clock.instant();
    List<Attempts> found =
        jdbc.query(
            "SELECT * FROM login_attempts WHERE kind = ? AND client = ?",
            this::attempts,
            kind,
            client);
    return !found.isEmpty() && found.getFirst().lockedAt(now)
        ? Duration.between(now, found.getFirst().lockedUntil())
        : Duration.ZERO;
  }

  /** Ends a reserved attempt as a failure; the {@link #MAX_FAILURES}th locks the client out. */
  public void recordFailure(String client) {
    Instant now = clock.instant();
    forgetExpired(now);
    transaction.executeWithoutResult(
        status -> {
          Attempts attempts = lockCreating(client, now).at(now);
          int failures = attempts.failures() + 1;
          store(
              client,
              new Attempts(
                  failures,
                  attempts.failures() == 0 ? now : attempts.firstFailure(),
                  failures >= MAX_FAILURES ? now.plus(LOCKOUT) : null,
                  Math.max(0, attempts.inProgress() - 1),
                  attempts.reservedAt(),
                  now));
        });
  }

  /** Ends a reserved attempt as a success, which also clears the client's failures. */
  public void recordSuccess(String client) {
    transaction.executeWithoutResult(
        status ->
            jdbc.update("DELETE FROM login_attempts WHERE kind = ? AND client = ?", kind, client));
  }

  /** Ends a reserved attempt that was neither a success nor a failure, without counting it. */
  public void release(String client) {
    transaction.executeWithoutResult(
        status -> {
          List<Attempts> found =
              jdbc.query(
                  "SELECT * FROM login_attempts WHERE kind = ? AND client = ? FOR UPDATE",
                  this::attempts,
                  kind,
                  client);
          if (!found.isEmpty() && found.getFirst().inProgress() > 0) {
            Attempts attempts = found.getFirst();
            // The row's last update stays as it was: nothing was counted.
            store(
                client,
                new Attempts(
                    attempts.failures(),
                    attempts.firstFailure(),
                    attempts.lockedUntil(),
                    attempts.inProgress() - 1,
                    attempts.reservedAt(),
                    attempts.updated()));
          }
        });
  }

  /** A wait for a message to the user, in whole minutes rounded up: "1 minute", "15 minutes". */
  public static String describe(Duration wait) {
    long minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
    return minutes + (minutes == 1 ? " minute" : " minutes");
  }

  /** How many clients are tracked for this kind. */
  int trackedClients() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM login_attempts WHERE kind = ?", Integer.class, kind);
  }

  // The client's row, made first if there is none, locked until the transaction ends. One
  // statement, so that a row deleted meanwhile (by a success, or another server forgetting it) is
  // made again rather than missed: two servers making it at once both get the one that was made.
  private Attempts lockCreating(String client, Instant now) {
    return jdbc.queryForObject(
        "INSERT INTO login_attempts (kind, client, failures, in_progress, updated_at)"
            + " VALUES (?, ?, 0, 0, ?)"
            + " ON CONFLICT (kind, client) DO UPDATE SET kind = EXCLUDED.kind RETURNING *",
        this::attempts,
        kind,
        client,
        time(now));
  }

  private void store(String client, Attempts attempts) {
    jdbc.update(
        "UPDATE login_attempts SET failures = ?, first_failure = ?, locked_until = ?,"
            + " in_progress = ?, reserved_at = ?, updated_at = ? WHERE kind = ? AND client = ?",
        attempts.failures(),
        time(attempts.firstFailure()),
        time(attempts.lockedUntil()),
        attempts.inProgress(),
        time(attempts.reservedAt()),
        time(attempts.updated()),
        kind,
        client);
  }

  // Every kind's, which all last as long; an index on the time keeps it cheap.
  // At most once a minute on each server, rather than on every attempt.
  private void forgetExpired(Instant now) {
    Instant last = lastForgotten.get();
    if (last != null && last.plus(FORGET_EVERY).isAfter(now)) {
      return;
    }
    if (lastForgotten.compareAndSet(last, now)) {
      jdbc.update("DELETE FROM login_attempts WHERE updated_at <= ?", time(now.minus(RETENTION)));
    }
  }

  private Attempts attempts(ResultSet row, int number) throws SQLException {
    return new Attempts(
        row.getInt("failures"),
        instant(row, "first_failure"),
        instant(row, "locked_until"),
        row.getInt("in_progress"),
        instant(row, "reserved_at"),
        instant(row, "updated_at"));
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static OffsetDateTime time(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
