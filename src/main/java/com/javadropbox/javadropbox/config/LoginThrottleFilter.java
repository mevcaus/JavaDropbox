package com.javadropbox.javadropbox.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses sign-in attempts from an address the {@link LoginAttemptLimiter} has locked out, and
 * otherwise reserves the attempt before the password is checked. Form login's success and failure
 * handlers end the reservation through {@link #recordSuccess} and {@link #recordFailure}.
 */
public class LoginThrottleFilter extends OncePerRequestFilter {

  // Set while this request holds a reserved attempt that no handler has ended yet.
  private static final String RESERVED = LoginThrottleFilter.class.getName() + ".RESERVED";

  private final LoginAttemptLimiter limiter;
  private final RequestMatcher loginRequest;

  /**
   * @param loginRequest the same matcher form login uses to pick the requests it authenticates, so
   *     that no spelling of the login URL (such as a percent-encoded one) reaches authentication
   *     without passing the throttle
   */
  public LoginThrottleFilter(LoginAttemptLimiter limiter, RequestMatcher loginRequest) {
    this.limiter = limiter;
    this.loginRequest = loginRequest;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !loginRequest.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String client = request.getRemoteAddr();
    Duration wait = limiter.tryAcquire(client);
    if (wait.isZero()) {
      request.setAttribute(RESERVED, Boolean.TRUE);
      try {
        chain.doFilter(request, response);
      } finally {
        // Neither handler ran, so the request ended some other way: give the attempt back.
        if (request.getAttribute(RESERVED) != null) {
          request.removeAttribute(RESERVED);
          limiter.release(client);
        }
      }
      return;
    }

    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(wait.toSeconds()));
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response
        .getWriter()
        .write(
            "{\"message\":\"Too many failed sign-in attempts. Try again in "
                + LoginAttemptLimiter.describe(wait)
                + ".\"}");
  }

  /** Ends the request's reserved attempt as a successful sign-in. */
  public void recordSuccess(HttpServletRequest request) {
    request.removeAttribute(RESERVED);
    limiter.recordSuccess(request.getRemoteAddr());
  }

  /** Ends the request's reserved attempt as a failed sign-in. */
  public void recordFailure(HttpServletRequest request) {
    request.removeAttribute(RESERVED);
    limiter.recordFailure(request.getRemoteAddr());
  }
}
