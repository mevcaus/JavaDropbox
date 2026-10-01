package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import jakarta.servlet.ServletContext;
import java.io.File;
import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Upload requests Tomcat cannot parse. MockMvc skips Tomcat's multipart parsing, so these run a
 * real server and talk HTTP to it, signed in like a browser.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Uploads Tomcat cannot parse")
class MultipartErrorIntegrationTests {

  @TempDir static Path servingDir;

  @DynamicPropertySource
  static void overrideServingDirectory(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
  }

  @LocalServerPort private int port;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ServletWebServerApplicationContext server;

  private final CookieManager cookies = new CookieManager();
  private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

  @BeforeEach
  void signIn() throws Exception {
    users.save(new User("owner", passwordEncoder.encode("password"), "ROLE_ADMIN"));
    send(HttpRequest.newBuilder(uri("/api/storage")).GET()); // sets the XSRF-TOKEN cookie
    HttpResponse<String> login =
        send(
            HttpRequest.newBuilder(uri("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=owner&password=password")));
    assertThat(login.statusCode()).as("sign-in").isEqualTo(200);
  }

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  @Test
  @DisplayName("a server-side failure storing the upload is a 500, not the client's fault")
  void serverFaultIsA500() throws Exception {
    File tempDir = (File) server.getServletContext().getAttribute(ServletContext.TEMPDIR);
    Files.createDirectories(tempDir.toPath());
    assertThat(tempDir.setWritable(false, false)).isTrue();
    try {
      assumeFalse(Files.isWritable(tempDir.toPath()), "running as root, which can write anyway");
      HttpResponse<String> response = upload(multipartBody("a.txt", "content", true));

      assertThat(response.statusCode()).isEqualTo(500);
      assertThat(response.body()).doesNotContain(tempDir.getPath());
    } finally {
      tempDir.setWritable(true, false);
    }
  }

  @Test
  @DisplayName("a malformed upload is still a 400")
  void malformedRequestIsA400() throws Exception {
    HttpResponse<String> response = upload(multipartBody("a.txt", "content", false));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("Invalid multipart request");
  }

  private static final String BOUNDARY = "----boundary";

  // Without the closing boundary the body ends in the middle of the part.
  private static String multipartBody(String name, String content, boolean complete) {
    return "--"
        + BOUNDARY
        + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
        + name
        + "\"\r\nContent-Type: text/plain\r\n\r\n"
        + content
        + (complete ? "\r\n--" + BOUNDARY + "--\r\n" : "");
  }

  private HttpResponse<String> upload(String body) throws Exception {
    return send(
        HttpRequest.newBuilder(uri("/api/files?path="))
            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
            .POST(HttpRequest.BodyPublishers.ofString(body)));
  }

  private HttpResponse<String> send(HttpRequest.Builder request)
      throws IOException, InterruptedException {
    cookies.getCookieStore().getCookies().stream()
        .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
        .map(HttpCookie::getValue)
        .findFirst()
        .ifPresent(token -> request.header("X-XSRF-TOKEN", token));
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
