package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import jakarta.servlet.MultipartConfigElement;
import java.io.File;
import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Upload requests Tomcat cannot parse. MockMvc skips Tomcat's multipart parsing, so these run a
 * real server and talk HTTP to it, signed in like a browser.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Uploads Tomcat cannot parse")
@TempServingDirectory
class MultipartErrorIntegrationTests {

  @LocalServerPort private int port;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MultipartConfigElement multipartConfig;

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
    // Where Tomcat spools uploads.
    File tempDir = new File(multipartConfig.getLocation());
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

  @Test
  @DisplayName("an upload with more parts than the server takes says how many it takes")
  void tooManyPartsSaysTheLimit() throws Exception {
    // 60 files and the path: more than the 50 parts application.properties allows.
    HttpResponse<String> response = upload(multipartWithFiles(60));

    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(response.body()).contains("at most 49 files");
  }

  @Test
  @DisplayName("an upload with as many files as the limits say is stored")
  void filesUpToTheLimitAreStored() throws Exception {
    HttpResponse<String> response = upload(multipartWithFiles(49));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(response.body()).contains("Uploaded 49 files");
  }

  private static final String BOUNDARY = "----boundary";

  // count small files and the path part, as the frontend sends them.
  private static String multipartWithFiles(int count) {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < count; i++) {
      body.append("--")
          .append(BOUNDARY)
          .append("\r\nContent-Disposition: form-data; name=\"files\"; filename=\"f")
          .append(i)
          .append(".txt\"\r\nContent-Type: text/plain\r\n\r\ncontent ")
          .append(i)
          .append("\r\n");
    }
    return body.append("--")
        .append(BOUNDARY)
        .append("\r\nContent-Disposition: form-data; name=\"path\"\r\n\r\n\r\n--")
        .append(BOUNDARY)
        .append("--\r\n")
        .toString();
  }

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
