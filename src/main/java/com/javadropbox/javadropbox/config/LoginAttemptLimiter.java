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

  // Once this long has passed since a client's last failure, both its window and any lockout that
  // failure started are over, so the entry no longer matters.
  private static final Duration RETENTION = WINDOW.compareTo(LOCKOUT) > 0 ? WINDOW : LOCKOUT;

  private record Attempts(
      int failures, Instant firstFailure, Instant lockedUntil, Instant updated) {}

  // Kept in order of last update, oldest first (an entry is re-inserted whenever it changes), so
  // expired entries and the next one to evict are always at the head. Guarded by this.
  private final LinkedHashMap<String, Attempts> byClient = new LinkedHashMap<>();
  private final Clock clock;

  public LoginAttemptLimiter(Clock clock) {
    this.clock = clock;
  }

  /** How long the client must still wait, or {@link Duration#ZERO} if it may try now. */
  public synchronized Duration retryAfter(String client) {
    Attempts attempts = byClient.get(client);
    if (attempts == null || attempts.lockedUntil() == null) {
      return Duration.ZERO;
    }
    Duration remaining = Duration.between(clock.instant(), attempts.lockedUntil());
    return remaining.isNegative() ? Duration.ZERO : remaining;
  }

  public synchronized void recordFailure(String client) {
    Instant now = clock.instant();
    forgetExpired(now);

    Attempts previous = byClient.remove(client);
    boolean fresh =
        previous == null
            || previous.firstFailure().plus(WINDOW).isBefore(now)
            || (previous.lockedUntil() != null && !previous.lockedUntil().isAfter(now));
    int failures = fresh ? 1 : previous.failures() + 1;
    Instant first = fresh ? now : previous.firstFailure();
    Instant lockedUntil = failures >= MAX_FAILURES ? now.plus(LOCKOUT) : null;
    byClient.put(client, new Attempts(failures, first, lockedUntil, now));

    if (byClient.size() > MAX_CLIENTS) {
      Iterator<String> oldest = byClient.keySet().iterator();
      oldest.next();
      oldest.remove();
    }
  }

  public synchronized void recordSuccess(String client) {
    byClient.remove(client);
  }

  /** How many clients are currently tracked. */
  synchronized int trackedClients() {
    return byClient.size();
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
