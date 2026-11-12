package com.javadropbox.javadropbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.MigratedDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@DisplayName("Login attempt limiter")
@Timeout(60)
class LoginAttemptLimiterTests {

  private static final String CLIENT = "203.0.113.7";

  // One database for the class, emptied after each test.
  private static final DataSource DATABASE = MigratedDatabase.create();

  private final JdbcTemplate jdbc = new JdbcTemplate(DATABASE);
  private final PlatformTransactionManager transactions =
      new DataSourceTransactionManager(DATABASE);
  private final MutableClock clock = new MutableClock();
  private final LoginAttemptLimiter limiter = server(LoginAttemptLimiter.SIGN_IN);

  @AfterEach
  void empty() {
    jdbc.update("DELETE FROM login_attempts");
  }

  // Another server's limiter, or another kind's, on the same database.
  private LoginAttemptLimiter server(String kind) {
    return new LoginAttemptLimiter(jdbc, transactions, clock, kind);
  }

  @Test
  @DisplayName("locks an address out after five failures, for fifteen minutes")
  void locksAfterFiveFailures() {
    fail(4);
    assertThat(limiter.retryAfter(CLIENT)).isZero();

    fail(1);
    assertThat(limiter.retryAfter(CLIENT)).isEqualTo(Duration.ofMinutes(15));

    clock.advance(Duration.ofMinutes(15));
    assertThat(limiter.retryAfter(CLIENT)).isZero();
  }

  @Test
  @DisplayName("a lockout only affects the address that failed")
  void lockoutIsPerAddress() {
    fail(5);

    assertThat(limiter.retryAfter("198.51.100.1")).isZero();
  }

  @Test
  @DisplayName("a successful sign-in clears the count")
  void successResets() {
    fail(4);
    limiter.recordSuccess(CLIENT);
    fail(4);

    assertThat(limiter.retryAfter(CLIENT)).isZero();
  }

  @Test
  @DisplayName("failures spread over more than the window do not add up")
  void oldFailuresExpire() {
    fail(4);
    clock.advance(Duration.ofMinutes(16));
    fail(4);

    assertThat(limiter.retryAfter(CLIENT)).isZero();
  }

  @Test
  @DisplayName("after a lockout ends, the count starts again")
  void countRestartsAfterLockout() {
    fail(5);
    clock.advance(Duration.ofMinutes(15));
    fail(1);

    assertThat(limiter.retryAfter(CLIENT)).isZero();
  }

  @Test
  @DisplayName("attempts still in progress count towards the limit")
  void attemptsInProgressCount() {
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryAcquire(CLIENT)).isZero();
    }

    assertThat(limiter.tryAcquire(CLIENT)).isPositive();
  }

  @Test
  @DisplayName("an attempt in progress becomes a failure, and the fifth locks the address out")
  void reservedAttemptsBecomeFailures() {
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryAcquire(CLIENT)).isZero();
      limiter.recordFailure(CLIENT);
    }

    assertThat(limiter.tryAcquire(CLIENT)).isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  @DisplayName("an attempt that ends without a verdict does not count")
  void releasedAttemptsDoNotCount() {
    for (int i = 0; i < 10; i++) {
      assertThat(limiter.tryAcquire(CLIENT)).isZero();
      limiter.release(CLIENT);
    }

    assertThat(limiter.retryAfter(CLIENT)).isZero();
  }

  @Test
  @DisplayName("a lockout outlasting the window is still enforced")
  void lockoutOutlastsTheWindow() {
    fail(4);
    clock.advance(Duration.ofMinutes(14));
    fail(1);
    clock.advance(Duration.ofMinutes(10));

    assertThat(limiter.tryAcquire(CLIENT)).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  @DisplayName("servers share the count: failures on one lock the address out on the other")
  void serversShareTheCount() {
    LoginAttemptLimiter other = server(LoginAttemptLimiter.SIGN_IN);
    fail(3);
    other.recordFailure(CLIENT);
    other.recordFailure(CLIENT);

    assertThat(limiter.retryAfter(CLIENT)).isEqualTo(Duration.ofMinutes(15));
    assertThat(other.tryAcquire(CLIENT)).isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  @DisplayName("a burst across two servers at once gets no more attempts than one server would")
  void burstAcrossServers() throws Exception {
    LoginAttemptLimiter other = server(LoginAttemptLimiter.SIGN_IN);
    List<Callable<Duration>> attempts = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      LoginAttemptLimiter on = i % 2 == 0 ? limiter : other;
      attempts.add(() -> on.tryAcquire(CLIENT));
    }
    List<Future<Duration>> results;
    try (ExecutorService threads = Executors.newFixedThreadPool(16)) {
      results = threads.invokeAll(attempts);
    }

    int reserved = 0;
    for (Future<Duration> result : results) {
      reserved += result.get().isZero() ? 1 : 0;
    }
    assertThat(reserved).isEqualTo(LoginAttemptLimiter.MAX_FAILURES);
  }

  // A success (another request from the same address) or another server forgetting the row can
  // delete it between an attempt finding it and locking it.
  @Test
  @DisplayName("a row deleted while attempts are made on it is made again, without an error")
  void rowDeletedMeanwhile() throws Exception {
    LoginAttemptLimiter other = server(LoginAttemptLimiter.SIGN_IN);
    try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
      Future<?> clearing =
          threads.submit(
              () -> {
                for (int i = 0; i < 300; i++) {
                  other.recordSuccess(CLIENT);
                }
              });
      Future<?> failing =
          threads.submit(
              () -> {
                for (int i = 0; i < 300; i++) {
                  if (limiter.tryAcquire(CLIENT).isZero()) {
                    limiter.recordFailure(CLIENT);
                  }
                }
              });
      clearing.get();
      failing.get();
    }

    // Every attempt was ended, so whatever row is left has none in progress.
    assertThat(
            jdbc.queryForList(
                "SELECT in_progress FROM login_attempts WHERE client = ?", Integer.class, CLIENT))
        .allMatch(inProgress -> inProgress == 0);
  }

  @Test
  @DisplayName("a client locked out is answered without writing to its row")
  void lockedOutClientIsAnsweredFromARead() {
    fail(5);
    String before = row();

    clock.advance(Duration.ofMinutes(1));
    assertThat(limiter.tryAcquire(CLIENT)).isEqualTo(Duration.ofMinutes(14));

    assertThat(row()).isEqualTo(before);
  }

  private String row() {
    return jdbc.queryForObject(
        "SELECT concat_ws(',', failures, in_progress, updated_at) FROM login_attempts"
            + " WHERE client = ?",
        String.class,
        CLIENT);
  }

  @Test
  @DisplayName("sign-ins and setup codes are counted apart")
  void kindsAreCountedApart() {
    fail(5);

    assertThat(server(LoginAttemptLimiter.SETUP_CODE).retryAfter(CLIENT)).isZero();
  }

  @Test
  @DisplayName("a reservation a server never ended stops counting after a while")
  void abandonedReservationsExpire() {
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryAcquire(CLIENT)).isZero();
    }
    assertThat(limiter.tryAcquire(CLIENT)).isPositive();

    clock.advance(LoginAttemptLimiter.RESERVATION.plusSeconds(1));

    assertThat(limiter.tryAcquire(CLIENT)).isZero();
  }

  @Test
  @DisplayName("an address is forgotten once its window and lockout are both over")
  void expiredClientsAreForgotten() {
    fail(5);
    for (int i = 0; i < 100; i++) {
      limiter.recordFailure("client-" + i);
    }

    clock.advance(Duration.ofMinutes(15));
    limiter.recordFailure("latecomer");

    assertThat(limiter.trackedClients()).isEqualTo(1);
  }

  private void fail(int times) {
    for (int i = 0; i < times; i++) {
      limiter.recordFailure(CLIENT);
    }
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
