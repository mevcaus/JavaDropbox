package com.javadropbox.javadropbox.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a password or setup code in the URL of a request that changes something, sign-in
 * included, with a 400.
 *
 * <p>A form endpoint declares {@code consumes}, but that only checks the Content-Type header: the
 * servlet merges the query string into the parameters, so a form-typed request with {@code
 * ?password=...} and an empty body would still be taken. A URL ends up in access logs, proxies and
 * browser history, so the request is refused before anything reads it. Names are matched after
 * percent-decoding, as Tomcat decodes them, so {@code pass%77ord} is caught too; a query string
 * that can't be decoded is refused.
 */
public class SecretsInUrlFilter extends OncePerRequestFilter {

  private static final Set<String> SECRETS = Set.of("password", "code");
  private static final Set<String> CHANGES = Set.of("POST", "PUT", "PATCH", "DELETE");

  private final ObjectMapper json;

  public SecretsInUrlFilter(ObjectMapper json) {
    this.json = json;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getQueryString() == null || !CHANGES.contains(request.getMethod());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String secret;
    try {
      secret = secretIn(request.getQueryString());
    } catch (IllegalArgumentException malformed) {
      refuse(response, "The URL's query string is malformed.");
      return;
    }
    if (secret == null) {
      chain.doFilter(request, response);
      return;
    }
    refuse(response, "Send the " + secret + " in the request body, not in the URL.");
  }

  // Names are decoded as Tomcat decodes them before anything reads a parameter, so pass%77ord
  // counts as password.
  private static @Nullable String secretIn(String query) {
    return Arrays.stream(query.split("&"))
        .map(pair -> pair.split("=", 2)[0])
        .map(name -> URLDecoder.decode(name, StandardCharsets.UTF_8))
        .filter(SECRETS::contains)
        .sorted()
        .findFirst()
        .orElse(null);
  }

  private void refuse(HttpServletResponse response, String message) throws IOException {
    response.setStatus(HttpStatus.BAD_REQUEST.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    json.writeValue(response.getWriter(), Map.of("message", message));
  }
}
