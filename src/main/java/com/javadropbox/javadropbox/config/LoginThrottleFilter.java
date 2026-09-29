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
import org.springframework.web.filter.OncePerRequestFilter;

/** Refuses sign-in attempts from an address the {@link LoginAttemptLimiter} has locked out. */
public class LoginThrottleFilter extends OncePerRequestFilter {

  private final LoginAttemptLimiter limiter;

  public LoginThrottleFilter(LoginAttemptLimiter limiter) {
    this.limiter = limiter;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !("POST".equals(request.getMethod()) && "/login".equals(path));
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
