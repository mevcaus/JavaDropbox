package com.javadropbox.javadropbox.exception;

import java.time.Duration;

/** The caller is being throttled, e.g. after too many wrong setup codes. Maps to 429. */
public class TooManyRequestsException extends RuntimeException {

  private final Duration retryAfter;

  public TooManyRequestsException(String message, Duration retryAfter) {
    super(message);
    this.retryAfter = retryAfter;
  }

  /** How long the caller should wait before trying again. */
  public Duration getRetryAfter() {
    return retryAfter;
  }
}
