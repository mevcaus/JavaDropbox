package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The sign-in throttle on a real Tomcat, which decodes URLs and serves requests in parallel the way
 * production does. The test client connects from loopback, which production trusts as a proxy, so
 * each test gives itself its own client address with X-Forwarded-For.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Login throttle")
class LoginThrottleIntegrationTests {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    MainProperties.register(
        registry, "server.forward-headers-strategy", "server.tomcat.remoteip.internal-proxies");
  }

  @LocalServerPort private int port;

  @Autowired private UserRepository userRepository;

  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private JdbcTemplate jdbc;

  private HttpTestClient http;

  @BeforeEach
  void setUp() {
    http = new HttpTestClient(port);
    if (userRepository.count() == 0) {
      userRepository.save(new User("owner", passwordEncoder.encode("correct-horse"), "ROLE_ADMIN"));
    }
  }

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  @Test
  @DisplayName("a percent-encoded /login is throttled like /login")
  void encodedLoginPathIsThrottled() {
    Map<String, String> client = Map.of("X-Forwarded-For", "198.51.100.21");
    for (int i = 0; i < 5; i++) {
      assertThat(http.login("owner", "wrong", client).statusCode()).isEqualTo(401);
    }

    assertThat(http.login("/logi%6E", "owner", "correct-horse", client).statusCode())
        .isEqualTo(429);
    assertThat(http.login("/%6Cogin", "owner", "correct-horse", client).statusCode())
        .isEqualTo(429);
  }

  @Test
  @DisplayName("failures on a percent-encoded /login count towards the lockout")
  void encodedLoginPathFailuresCount() {
    Map<String, String> client = Map.of("X-Forwarded-For", "198.51.100.22");
    for (int i = 0; i < 5; i++) {
      assertThat(http.login("/logi%6E", "owner", "wrong", client).statusCode()).isEqualTo(401);
    }

    assertThat(http.login("owner", "correct-horse", client).statusCode()).isEqualTo(429);
  }

  @Test
  @DisplayName("a burst of parallel attempts gets no more password checks than the limit")
  void parallelBurstIsLimited() throws Exception {
    int attempts = 30;
    // Fetch the CSRF tokens first so that the burst is nothing but login attempts.
    List<Map<String, String>> requests = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      requests.add(http.withCsrf(Map.of("X-Forwarded-For", "198.51.100.23")));
    }
    Map<String, String> form = Map.of("username", "owner", "password", "wrong");

    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    List<Integer> statuses = new ArrayList<>();
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Integer>> responses = new ArrayList<>();
      for (Map<String, String> headers : requests) {
        responses.add(
            pool.submit(
                () -> {
                  start.await();
                  return http.postForm("/login", form, headers).statusCode();
                }));
      }
      start.countDown();
      for (Future<Integer> response : responses) {
        statuses.add(response.get());
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(statuses).containsOnly(401, 429);
    assertThat(statuses).filteredOn(status -> status == 401).hasSize(5);
  }
}
