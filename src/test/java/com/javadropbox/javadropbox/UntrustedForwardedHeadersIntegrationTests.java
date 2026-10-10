package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.config.LoginAttemptLimiter;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.LocalFileStore;
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
 * Forwarded headers sent by a client that is not a trusted proxy. The test client connects from
 * loopback, which production trusts, so here the trusted-proxy list names a different address and
 * the test client is just another client on the network.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Forwarded headers from an untrusted client")
class UntrustedForwardedHeadersIntegrationTests {

  private static final String CLIENT = "127.0.0.1";

  @TempDir static Path servingDir;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
    MainProperties.register(
        registry, "server.forward-headers-strategy", "server.servlet.session.cookie.same-site");
    registry.add("server.tomcat.remoteip.internal-proxies", () -> "192\\.0\\.2\\.1");
  }

  @LocalServerPort private int port;

  @Autowired private UserRepository userRepository;

  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private LoginAttemptLimiter limiter;

  @Autowired private JdbcTemplate jdbc;

  private HttpTestClient http;

  @Autowired private StoragePaths storagePaths;
  @Autowired private LocalFileStore localStore;

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
    home = localStore.path(storagePaths.home(owner).key());
    Files.writeString(home.resolve("shared.txt"), "share me");
    // Every request in this class comes from the same address; start each test unthrottled.
    limiter.recordSuccess(CLIENT);
  }

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  @Test
  @DisplayName("a locked-out client cannot get back in by sending a new X-Forwarded-For")
  void spoofedForwardedForDoesNotEscapeTheLockout() {
    for (int i = 0; i < 5; i++) {
      assertThat(http.login("owner", "wrong", Map.of()).statusCode()).isEqualTo(401);
    }

    for (int i = 0; i < 10; i++) {
      assertThat(http.login("owner", "wrong", Map.of("X-Forwarded-For", "198.51.100." + i)))
          .extracting(HttpResponse::statusCode)
          .isEqualTo(429);
    }
    assertThat(
            http.login("owner", "correct-horse", Map.of("X-Forwarded-For", "198.51.100.250"))
                .statusCode())
        .isEqualTo(429);
  }

  @Test
  @DisplayName("a client cannot lock someone else out by claiming their address")
  void spoofedForwardedForDoesNotLockOutAnotherAddress() {
    for (int i = 0; i < 5; i++) {
      http.login("owner", "wrong", Map.of("X-Forwarded-For", "198.51.100.20"));
    }

    assertThat(limiter.retryAfter("198.51.100.20")).isZero();
    assertThat(limiter.retryAfter(CLIENT)).isPositive();
  }

  @Test
  @DisplayName("a client claiming https over plain http gets no Secure session cookie")
  void spoofedForwardedProtoDoesNotMarkTheCookieSecure() {
    String cookie =
        HttpTestClient.setCookie(
            http.login("owner", "correct-horse", Map.of("X-Forwarded-Proto", "https")),
            "JSESSIONID");

    assertThat(cookie).contains("SameSite=Lax").doesNotContain("Secure");
  }

  @Test
  @DisplayName("a client's X-Forwarded-Proto and X-Forwarded-Host do not change share links")
  void spoofedForwardedHostDoesNotChangeShareLinks() {
    String session =
        HttpTestClient.cookieValue(http.login("owner", "correct-horse", Map.of()), "JSESSIONID");

    HttpResponse<String> share =
        http.postForm(
            "/api/share",
            Map.of("path", "shared.txt"),
            http.withCsrf(
                Map.of(
                    "Cookie", "JSESSIONID=" + session,
                    "X-Forwarded-Proto", "https",
                    "X-Forwarded-Host", "evil.example")));

    assertThat(share.statusCode()).isEqualTo(200);
    assertThat(share.body()).contains("\"url\":\"" + http.baseUrl() + "/share/");
  }
}
