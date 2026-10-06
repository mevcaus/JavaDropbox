package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.service.DemoService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives the demo its daily reset before the first request after the reset time is handled (see
 * DemoService for why this is not a scheduled task).
 */
@Component
@Profile("demo")
public class DemoResetFilter extends OncePerRequestFilter {

  private final DemoService demo;

  public DemoResetFilter(DemoService demo) {
    this.demo = demo;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    demo.resetIfDue();
    filterChain.doFilter(request, response);
  }
}
