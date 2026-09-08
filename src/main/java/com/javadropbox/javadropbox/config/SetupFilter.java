package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(1)
public class SetupFilter extends OncePerRequestFilter {

  private final AuthService authService;

  // -- setup filter is ignored in test suite --
  @Value("${app.setup.filter.enabled:true}")
  private boolean filterEnabled;

  public SetupFilter(AuthService authService) {
    this.authService = authService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    if (!filterEnabled) {
      filterChain.doFilter(request, response);
      return;
    }

    String requestURI = request.getRequestURI();

    if (authService.isSetupRequired()) {
      if (requestURI.equals("/setup")
          || requestURI.startsWith("/css/")
          || requestURI.startsWith("/js/")
          || requestURI.startsWith("/images/")
          || requestURI.equals("/JavaDropbox_favicon.png")
          || isApiDocsPath(requestURI)) {
        filterChain.doFilter(request, response);
      } else {
        response.sendRedirect("/setup");
      }
    } else {
      if (requestURI.equals("/setup")) {
        response.sendRedirect("/login");
      } else {
        filterChain.doFilter(request, response);
      }
    }
  }

  // Swagger UI and the OpenAPI spec are permitAll in SecurityConfig; exempt them here too so
  // the API reference stays reachable before the first user is created.
  private static boolean isApiDocsPath(String requestURI) {
    return requestURI.equals("/swagger-ui.html")
        || requestURI.startsWith("/swagger-ui/")
        || requestURI.equals("/v3/api-docs")
        || requestURI.startsWith("/v3/api-docs/");
  }
}
