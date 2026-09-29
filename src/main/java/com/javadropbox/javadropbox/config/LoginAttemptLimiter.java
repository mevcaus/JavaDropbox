package com.javadropbox.javadropbox.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing: after {@link #MAX_FAILURES} failed sign-ins from one address within
 * {@link #WINDOW}, that address is refused for {@link #LOCKOUT}. Kept in memory, which is enough
 * for a single instance.
 */
public class LoginAttemptLimiter {

  static final int MAX_FAILURES = 5;
  static final Duration WINDOW = Duration.ofMinutes(15);
  static final Duration LOCKOUT = Duration.ofMinutes(15);

  // Bounds memory if many addresses fail once and never come back.
  private static final int PRUNE_THRESHOLD = 10_000;

  private record Attempts(int failures, Instant firstFailure, Instant lockedUntil) {}

  private final Map<String, Attempts> byClient = new ConcurrentHashMap<>();
  private final Clock clock;

  public LoginAttemptLimiter(Clock clock) {
    this.clock = clock;
  }

  /** How long the client must still wait, or {@link Duration#ZERO} if it may try now. */
  public Duration retryAfter(String client) {
    Attempts attempts = byClient.get(client);
    if (attempts == null || attempts.lockedUntil() == null) {
      return Duration.ZERO;
    }
    Duration remaining = Duration.between(clock.instant(), attempts.lockedUntil());
    return remaining.isNegative() ? Duration.ZERO : remaining;
  }

  public void recordFailure(String client) {
    Instant now = clock.instant();
    byClient.compute(
        client,
        (key, previous) -> {
          boolean fresh =
              previous == null
                  || previous.firstFailure().plus(WINDOW).isBefore(now)
                  || (previous.lockedUntil() != null && !previous.lockedUntil().isAfter(now));
          int failures = fresh ? 1 : previous.failures() + 1;
          Instant first = fresh ? now : previous.firstFailure();
          Instant lockedUntil = failures >= MAX_FAILURES ? now.plus(LOCKOUT) : null;
          return new Attempts(failures, first, lockedUntil);
        });
    if (byClient.size() > PRUNE_THRESHOLD) {
      byClient.values().removeIf(a -> a.firstFailure().plus(WINDOW).plus(LOCKOUT).isBefore(now));
    }
  }

  public void recordSuccess(String client) {
    byClient.remove(client);
  }
}
