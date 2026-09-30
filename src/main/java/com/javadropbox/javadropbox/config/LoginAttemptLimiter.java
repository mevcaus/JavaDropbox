package com.javadropbox.javadropbox.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * Slows down password guessing: after {@link #MAX_FAILURES} failed sign-ins from one address within
 * {@link #WINDOW}, that address is refused for {@link #LOCKOUT}. Kept in memory, which is enough
 * for a single instance.
 *
 * <p>Each attempt is reserved with {@link #tryAcquire} before the password is checked and then
 * ended with {@link #recordFailure}, {@link #recordSuccess} or {@link #release}. Attempts still in
 * progress count towards the limit, so a burst of parallel requests gets no more password checks
 * than sequential ones would.
 *
 * <p>At most {@link #MAX_CLIENTS} addresses are tracked at once, and each is forgotten as soon as
 * its failures can no longer lead to or extend a lockout, so neither memory nor the work per call
 * grows with the number of clients that ever failed. If more addresses than that fail within the
 * window, the one whose last failure is oldest is forgotten first.
 */
public class LoginAttemptLimiter {

  static final int MAX_FAILURES = 5;
  static final Duration WINDOW = Duration.ofMinutes(15);
  static final Duration LOCKOUT = Duration.ofMinutes(15);
  static final int MAX_CLIENTS = 10_000;

  // What a client is told to wait when its remaining attempts are all in progress: they finish
  // within a second or so, after which it is either locked out or may try again.
  private static final Duration BUSY_RETRY = Duration.ofSeconds(1);

  // Once this long has passed since a client's last update, both its window and any lockout its
  // failures started are over, so the entry no longer matters.
  private static final Duration RETENTION = WINDOW.compareTo(LOCKOUT) > 0 ? WINDOW : LOCKOUT;

  private record Attempts(
      int failures, Instant firstFailure, Instant lockedUntil, int inProgress, Instant updated) {

    static final Attempts NONE = new Attempts(0, null, null, 0, null);

    boolean lockedAt(Instant now) {
      return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** These attempts as they stand at {@code now}: failures from an ended window don't count. */
    Attempts at(Instant now) {
      boolean over =
          lockedUntil != null
              ? !lockedUntil.isAfter(now)
              : firstFailure != null && firstFailure.plus(WINDOW).isBefore(now);
      return over ? new Attempts(0, null, null, inProgress, updated) : this;
    }
  }

  // Kept in order of last update, oldest first (an entry is re-inserted whenever it changes), so
  // expired entries and the next one to evict are always at the head. Guarded by this.
  private final LinkedHashMap<String, Attempts> byClient = new LinkedHashMap<>();
  private final Clock clock;

  public LoginAttemptLimiter(Clock clock) {
    this.clock = clock;
  }

  /**
   * Reserves an attempt for the client if it may make one now. Returns {@link Duration#ZERO} when
   * the attempt is reserved, which the caller must then end with {@link #recordFailure}, {@link
   * #recordSuccess} or {@link #release}; otherwise how long the client should wait.
   */
  public synchronized Duration tryAcquire(String client) {
    Instant now = clock.instant();
    forgetExpired(now);

    Attempts attempts = byClient.getOrDefault(client, Attempts.NONE).at(now);
    if (attempts.lockedAt(now)) {
      return Duration.between(now, attempts.lockedUntil());
    }
    if (attempts.failures() + attempts.inProgress() >= MAX_FAILURES) {
      return BUSY_RETRY;
    }
    store(
        client,
        new Attempts(
            attempts.failures(), attempts.firstFailure(), null, attempts.inProgress() + 1, now));
    return Duration.ZERO;
  }

  /** How long the client must still wait, or {@link Duration#ZERO} if it is not locked out. */
  public synchronized Duration retryAfter(String client) {
    Instant now = clock.instant();
    Attempts attempts = byClient.getOrDefault(client, Attempts.NONE);
    return attempts.lockedAt(now) ? Duration.between(now, attempts.lockedUntil()) : Duration.ZERO;
  }

  /** Ends a reserved attempt as a failure; the {@link #MAX_FAILURES}th locks the client out. */
  public synchronized void recordFailure(String client) {
    Instant now = clock.instant();
    forgetExpired(now);

    Attempts attempts = byClient.getOrDefault(client, Attempts.NONE).at(now);
    int failures = attempts.failures() + 1;
    store(
        client,
        new Attempts(
            failures,
            attempts.failures() == 0 ? now : attempts.firstFailure(),
            failures >= MAX_FAILURES ? now.plus(LOCKOUT) : null,
            Math.max(0, attempts.inProgress() - 1),
            now));
  }

  /** Ends a reserved attempt as a success, which also clears the client's failures. */
  public synchronized void recordSuccess(String client) {
    byClient.remove(client);
  }

  /** Ends a reserved attempt that was neither a success nor a failure, without counting it. */
  public synchronized void release(String client) {
    Attempts attempts = byClient.get(client);
    if (attempts != null && attempts.inProgress() > 0) {
      // Replacing the value of an existing key keeps its place in the order, as it should: the
      // entry's last-update time does not change either.
      byClient.put(
          client,
          new Attempts(
              attempts.failures(),
              attempts.firstFailure(),
              attempts.lockedUntil(),
              attempts.inProgress() - 1,
              attempts.updated()));
    }
  }

  /** How many clients are currently tracked. */
  synchronized int trackedClients() {
    return byClient.size();
  }

  // Moves the client to the tail, as the most recently updated, and evicts the oldest if full.
  private void store(String client, Attempts attempts) {
    byClient.remove(client);
    byClient.put(client, attempts);
    if (byClient.size() > MAX_CLIENTS) {
      Iterator<String> oldest = byClient.keySet().iterator();
      oldest.next();
      oldest.remove();
    }
  }

  // Each entry is removed at most once, so this costs O(1) per call on average.
  private void forgetExpired(Instant now) {
    for (Iterator<Attempts> it = byClient.values().iterator(); it.hasNext(); ) {
      if (it.next().updated().plus(RETENTION).isAfter(now)) {
        return;
      }
      it.remove();
    }
  }
}
