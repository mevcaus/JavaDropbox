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

/** Refuses sign-in attempts from an address the {@link LoginAttemptLimiter} has locked out. */
public class LoginThrottleFilter extends OncePerRequestFilter {

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
    Duration wait = limiter.retryAfter(request.getRemoteAddr());
    if (wait.isZero()) {
      chain.doFilter(request, response);
      return;
    }

    long minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(wait.toSeconds()));
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response
        .getWriter()
        .write(
            "{\"message\":\"Too many failed sign-in attempts. Try again in "
                + minutes
                + (minutes == 1 ? " minute" : " minutes")
                + ".\"}");
  }
}
