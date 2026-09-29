package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sends every request to the setup page until the first account exists, and keeps the setup page
 * out of reach afterwards. Registered only inside the security filter chain (see
 * SecurityConfig#setupFilterRegistration).
 */
@Component
public class SetupFilter extends OncePerRequestFilter {

  private final AuthService authService;

  // Accounts are never deleted, so once one exists setup can never be needed again; stop asking
  // the database on every request from then on.
  private volatile boolean setupComplete;

  public SetupFilter(AuthService authService) {
    this.authService = authService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String uri = request.getRequestURI();

    if (!setupComplete) {
      if (authService.isSetupRequired()) {
        if (allowedDuringSetup(uri)) {
          filterChain.doFilter(request, response);
        } else {
          response.sendRedirect("/setup");
        }
        return;
      }
      setupComplete = true;
    }

    // The setup page is pointless once an account exists. A POST still reaches the controller,
    // which answers with a clear 409 rather than a redirect an API client would not expect.
    if (uri.equals("/setup") && "GET".equals(request.getMethod())) {
      response.sendRedirect("/login");
      return;
    }
    filterChain.doFilter(request, response);
  }

  // The setup page itself, the app shell and assets it needs to render, and the API docs (which
  // SecurityConfig also leaves public).
  private static boolean allowedDuringSetup(String uri) {
    return uri.equals("/setup")
        || uri.equals("/index.html")
        || uri.equals("/favicon.png")
        || uri.startsWith("/assets/")
        || uri.equals("/swagger-ui.html")
        || uri.startsWith("/swagger-ui/")
        || uri.equals("/v3/api-docs")
        || uri.startsWith("/v3/api-docs/");
  }
}
