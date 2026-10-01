package com.javadropbox.javadropbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Talks real HTTP to a {@code RANDOM_PORT} test server, for behaviour MockMvc skips: Tomcat's
 * forwarded-header valve, its URL decoding, and the cookies it writes. It keeps no cookie jar, so a
 * test sends every cookie explicitly and can inspect every Set-Cookie.
 */
final class HttpTestClient {

  static final String CSRF_COOKIE = "XSRF-TOKEN";
  static final String CSRF_HEADER = "X-XSRF-TOKEN";

  private final HttpClient client =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final String base;

  HttpTestClient(int port) {
    this.base = "http://localhost:" + port;
  }

  String baseUrl() {
    return base;
  }

  HttpResponse<String> get(String path, Map<String, String> headers) {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).GET();
    headers.forEach(request::header);
    return send(request.build());
  }

  HttpResponse<String> postForm(
      String path, Map<String, String> form, Map<String, String> headers) {
    String body =
        form.entrySet().stream()
            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(Collectors.joining("&"));
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(request::header);
    return send(request.build());
  }

  /** A CSRF token, obtained the way the app gets one: any response sets the cookie. */
  String csrfToken() {
    String token = cookieValue(get("/api/me", Map.of()), CSRF_COOKIE);
    if (token == null) {
      throw new IllegalStateException("the server set no " + CSRF_COOKIE + " cookie");
    }
    return token;
  }

  /** Headers carrying a valid CSRF token, plus {@code extra}. */
  Map<String, String> withCsrf(Map<String, String> extra) {
    String token = csrfToken();
    Map<String, String> headers = new LinkedHashMap<>(extra);
    headers.merge("Cookie", CSRF_COOKIE + "=" + token, (existing, csrf) -> existing + "; " + csrf);
    headers.put(CSRF_HEADER, token);
    return headers;
  }

  HttpResponse<String> login(String username, String password, Map<String, String> headers) {
    return login("/login", username, password, headers);
  }

  /** Signs in by posting to {@code path}, with a valid CSRF token and any extra headers. */
  HttpResponse<String> login(
      String path, String username, String password, Map<String, String> headers) {
    return postForm(path, Map.of("username", username, "password", password), withCsrf(headers));
  }

  HttpResponse<String> send(HttpRequest request) {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** The whole Set-Cookie header for {@code name}, attributes included, or null. */
  static String setCookie(HttpResponse<?> response, String name) {
    return response.headers().allValues("Set-Cookie").stream()
        .filter(h -> h.startsWith(name + "="))
        .findFirst()
        .orElse(null);
  }

  /** Just the value the response set for cookie {@code name}, or null. */
  static String cookieValue(HttpResponse<?> response, String name) {
    String header = setCookie(response, name);
    if (header == null) {
      return null;
    }
    String value = header.substring(name.length() + 1);
    int end = value.indexOf(';');
    return end < 0 ? value : value.substring(0, end);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
