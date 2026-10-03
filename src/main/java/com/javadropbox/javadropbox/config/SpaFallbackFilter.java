package com.javadropbox.javadropbox.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Serves the app shell when a browser opens a page the server has no route for, so the app's own
 * catch-all route can send it on (to the dashboard, or to sign-in without a session) instead of the
 * browser showing a bare 401. SpaController names the app's known routes; this covers anything else
 * a person types, bookmarks or refreshes.
 *
 * <p>Runs after Spring Security, which lets these requests through without a session (see
 * SecurityConfig) but still applies SetupFilter's first-run redirect first.
 */
@Component
public class SpaFallbackFilter extends OncePerRequestFilter {

  // Everything the server itself answers on a GET. A navigation under one of these keeps its real
  // response: an API 401 or 404, a share link's download or 404, the docs.
  private static final List<String> SERVER_PATHS =
      List.of("/api", "/share", "/swagger-ui", "/v3/api-docs", "/error");

  /**
   * Whether a request is a browser opening a page: a GET that asks for HTML, outside the server's
   * own paths, for something other than a file. A last path segment with a dot is a file (a missing
   * script should be a 404, not the app), and API clients that only accept JSON or {@code *}/{@code
   * *} are never answered with HTML.
   */
  public static boolean isNavigation(HttpServletRequest request) {
    if (!"GET".equals(request.getMethod())) {
      return false;
    }
    String path = request.getRequestURI().substring(request.getContextPath().length());
    for (String serverPath : SERVER_PATHS) {
      if (path.equals(serverPath) || path.startsWith(serverPath + "/")) {
        return false;
      }
    }
    if (path.substring(path.lastIndexOf('/') + 1).contains(".")) {
      return false;
    }
    return acceptsHtml(request.getHeader(HttpHeaders.ACCEPT));
  }

  private static boolean acceptsHtml(String accept) {
    if (accept == null) {
      return false;
    }
    try {
      return MediaType.parseMediaTypes(accept).stream()
          .anyMatch(
              type ->
                  "text".equals(type.getType())
                      && "html".equals(type.getSubtype())
                      && type.getQualityValue() > 0);
    } catch (InvalidMediaTypeException e) {
      return false;
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !isNavigation(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    request.getRequestDispatcher("/index.html").forward(request, response);
  }
}
