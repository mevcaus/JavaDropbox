package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.StoragePaths;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Requests arriving through a reverse proxy the app trusts. Production trusts loopback, and the
 * test client connects from loopback, so here the test client plays the proxy: the forwarded
 * headers it sends are the ones a real proxy would add.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Requests through a trusted proxy")
class TrustedProxyIntegrationTests {

  @TempDir static Path servingDir;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
    MainProperties.register(
        registry,
        "server.forward-headers-strategy",
        "server.tomcat.remoteip.internal-proxies",
        "server.servlet.session.cookie.same-site");
  }

  @LocalServerPort private int port;

  @Autowired private UserRepository userRepository;

  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private JdbcTemplate jdbc;

  private HttpTestClient http;

  @Autowired private StoragePaths storagePaths;

  private User owner;
  // The signed-in account's folder, where its files are.
  private Path home;

  @BeforeEach
  void setUp() throws IOException {
    http = new HttpTestClient(port);
    owner =
        userRepository
            .findByUsername("owner")
            .orElseGet(
                () ->
                    userRepository.save(
                        new User("owner", passwordEncoder.encode("correct-horse"), "ROLE_ADMIN")));
    home = storagePaths.home(owner).root();
    Files.writeString(home.resolve("shared.txt"), "share me");
  }

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  // The limiter is shared by the whole context, so each test signs in from an address of its own.

  @Test
  @DisplayName("the forwarded client address is the one locked out, not the proxy's")
  void lockoutFollowsTheForwardedAddress() {
    Map<String, String> client = Map.of("X-Forwarded-For", "198.51.100.7");
    for (int i = 0; i < 5; i++) {
      assertThat(http.login("owner", "wrong", client).statusCode()).isEqualTo(401);
    }

    assertThat(http.login("owner", "correct-horse", client).statusCode()).isEqualTo(429);
    assertThat(
            http.login("owner", "correct-horse", Map.of("X-Forwarded-For", "198.51.100.8"))
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("an entry the client put in front of the proxy's X-Forwarded-For is ignored")
  void clientSuppliedForwardedForEntryIsIgnored() {
    for (int i = 0; i < 5; i++) {
      http.login("owner", "wrong", Map.of("X-Forwarded-For", "198.51.100.9"));
    }

    // A proxy that appends (nginx's $proxy_add_x_forwarded_for) passes on whatever the client sent
    // first; the address it appended itself is the real one.
    assertThat(
            http.login(
                    "owner",
                    "correct-horse",
                    Map.of("X-Forwarded-For", "203.0.113.99, 198.51.100.9"))
                .statusCode())
        .isEqualTo(429);
  }

  @Test
  @DisplayName("the session cookie is SameSite=Lax, and Secure when the proxy forwarded https")
  void sessionCookieIsSecureBehindATlsProxy() {
    String plain =
        HttpTestClient.setCookie(
            http.login("owner", "correct-horse", Map.of("X-Forwarded-For", "198.51.100.11")),
            "JSESSIONID");
    String https =
        HttpTestClient.setCookie(
            http.login(
                "owner",
                "correct-horse",
                Map.of("X-Forwarded-For", "198.51.100.11", "X-Forwarded-Proto", "https")),
            "JSESSIONID");

    assertThat(plain).contains("SameSite=Lax").doesNotContain("Secure");
    assertThat(https).contains("SameSite=Lax").contains("Secure");
  }

  @Test
  @DisplayName("share links carry the public scheme and host the proxy forwards")
  void shareLinksUseTheForwardedSchemeAndHost() {
    Map<String, String> client = Map.of("X-Forwarded-For", "198.51.100.10");
    String session =
        HttpTestClient.cookieValue(http.login("owner", "correct-horse", client), "JSESSIONID");

    HttpResponse<String> share =
        http.postForm(
            "/api/share",
            Map.of("path", "shared.txt"),
            http.withCsrf(
                Map.of(
                    "Cookie", "JSESSIONID=" + session,
                    "X-Forwarded-For", "198.51.100.10",
                    "X-Forwarded-Proto", "https",
                    "X-Forwarded-Host", "files.example.com")));

    assertThat(share.statusCode()).isEqualTo(200);
    assertThat(share.body()).contains("\"url\":\"https://files.example.com/share/");
  }
}
