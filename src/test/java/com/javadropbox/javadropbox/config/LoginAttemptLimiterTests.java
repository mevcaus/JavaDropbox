package com.javadropbox.javadropbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Login attempt limiter")
class LoginAttemptLimiterTests {

  private static final String CLIENT = "203.0.113.7";

  private final MutableClock clock = new MutableClock();
  private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

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
