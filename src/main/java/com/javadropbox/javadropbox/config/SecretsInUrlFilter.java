package com.javadropbox.javadropbox.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Refuses a password or setup code in the URL of a request that changes something, sign-in
 * included, with a 400.
 *
 * <p>A form endpoint declares {@code consumes}, but that only checks the Content-Type header: the
 * servlet merges the query string into the parameters, so a form-typed request with {@code
 * ?password=...} and an empty body would still be taken. A URL ends up in access logs, proxies and
 * browser history, so the request is refused before anything reads it.
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
    Set<String> names =
        UriComponentsBuilder.newInstance()
            .query(request.getQueryString())
            .build()
            .getQueryParams()
            .keySet();
    String secret = names.stream().filter(SECRETS::contains).sorted().findFirst().orElse(null);
    if (secret == null) {
      chain.doFilter(request, response);
      return;
    }
    response.setStatus(HttpStatus.BAD_REQUEST.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    json.writeValue(
        response.getWriter(),
        Map.of("message", "Send the " + secret + " in the request body, not in the URL."));
  }
}
