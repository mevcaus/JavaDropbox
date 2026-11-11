package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.LocalFileStore;
import com.javadropbox.javadropbox.service.StoragePaths;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.session.JdbcSessionDataSourceScriptDatabaseInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sessions live in the spring_session table, which Flyway creates (V9). Each one is a row, so only
 * a sign-in starts one: a visitor who is refused, or who opens a share link, writes nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Sessions in the database")
@TempServingDirectory
class SessionStoreIntegrationTests {

  @LocalServerPort private int port;

  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ApplicationContext context;
  @Autowired private JdbcIndexedSessionRepository sessionRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private StoragePaths storagePaths;
  @Autowired private LocalFileStore localStore;

  private HttpTestClient http;
  private User owner;

  @BeforeEach
  void setUp() {
    http = new HttpTestClient(port);
    owner = users.save(new User("owner", passwordEncoder.encode("correct-horse"), User.ROLE_ADMIN));
  }

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  @Test
  @DisplayName("Spring Session's own schema script is off, so only Flyway makes the tables")
  void tablesComeFromFlyway() {
    assertThat(context.getBeansOfType(JdbcSessionDataSourceScriptDatabaseInitializer.class))
        .isEmpty();
    assertThat(
            jdbc.queryForList(
                "SELECT script FROM flyway_schema_history WHERE version = '9'", String.class))
        .containsExactly("V9__spring_session.sql");
  }

  @Test
  @DisplayName("signing in stores the session under its account, and signing out deletes it")
  void signInStoresTheSession() {
    HttpResponse<String> login = http.login("owner", "correct-horse", Map.of());
    assertThat(HttpTestClient.setCookie(login, "JSESSIONID"))
        .contains("HttpOnly")
        .contains("SameSite=Lax");
    String session = HttpTestClient.cookieValue(login, "JSESSIONID");
    assertThat(jdbc.queryForList("SELECT principal_name FROM spring_session", String.class))
        .containsExactly("owner");
    // server.servlet.session.timeout, 30 minutes unless it is set.
    assertThat(jdbc.queryForList("SELECT max_inactive_interval FROM spring_session", Integer.class))
        .containsExactly(1800);

    http.postForm("/logout", Map.of(), http.withCsrf(Map.of("Cookie", "JSESSIONID=" + session)));
    assertThat(sessions()).isZero();
  }

  @Test
  @DisplayName("the stored session does not hold the password hash")
  void passwordHashIsNotStored() {
    http.login("owner", "correct-horse", Map.of());
    String hash = users.findByUsername("owner").orElseThrow().getPassword();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM spring_session_attributes"
                    + " WHERE position(convert_to(?, 'UTF8') IN attribute_bytes) > 0",
                Integer.class,
                hash))
        .isZero();
  }

  @Test
  @DisplayName("signing in again gives the browser a new session, and the old one ends")
  void signingInReplacesTheSession() {
    String first =
        HttpTestClient.cookieValue(http.login("owner", "correct-horse", Map.of()), "JSESSIONID");

    String second =
        HttpTestClient.cookieValue(
            http.login("owner", "correct-horse", Map.of("Cookie", "JSESSIONID=" + first)),
            "JSESSIONID");

    assertThat(second).isNotNull().isNotEqualTo(first);
    assertThat(http.get("/api/me", Map.of("Cookie", "JSESSIONID=" + first)).statusCode())
        .isEqualTo(401);
    assertThat(http.get("/api/me", Map.of("Cookie", "JSESSIONID=" + second)).statusCode())
        .isEqualTo(200);
  }

  // As after an upgrade that changed a class stored in sessions: such a session is signed out
  // rather than failing every request until it expires.
  @Test
  @DisplayName("a session whose stored sign-in cannot be read is signed out, not an error")
  void unreadableSessionIsSignedOut() {
    String session =
        HttpTestClient.cookieValue(http.login("owner", "correct-horse", Map.of()), "JSESSIONID");
    jdbc.update(
        "UPDATE spring_session_attributes SET attribute_bytes = '\\x00'"
            + " WHERE attribute_name = 'SPRING_SECURITY_CONTEXT'");

    assertThat(http.get("/api/me", Map.of("Cookie", "JSESSIONID=" + session)).statusCode())
        .isEqualTo(401);
    assertThat(sessions()).isZero();
    assertThat(
            http.postForm(
                    "/logout", Map.of(), http.withCsrf(Map.of("Cookie", "JSESSIONID=" + session)))
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("the cleanup schedule is on by default")
  void cleanupScheduleRuns() {
    assertThat(ReflectionTestUtils.getField(sessionRepository, "taskScheduler")).isNotNull();
  }

  @Test
  @DisplayName("signing in deletes the sessions that have expired, and no others")
  void signInDeletesExpiredSessions() {
    long now = System.currentTimeMillis();
    jdbc.update(
        "INSERT INTO spring_session VALUES"
            + " ('00000000-0000-0000-0000-00000000000e', 'expired', ?, ?, 1800, ?, 'someone')",
        now - 3_600_000,
        now - 3_600_000,
        now - 1_800_000);
    jdbc.update(
        "INSERT INTO spring_session VALUES"
            + " ('00000000-0000-0000-0000-00000000000a', 'alive', ?, ?, 1800, ?, 'someone')",
        now,
        now,
        now + 1_800_000);

    assertThat(http.login("owner", "correct-horse", Map.of()).statusCode()).isEqualTo(200);

    assertThat(jdbc.queryForList("SELECT trim(session_id) FROM spring_session", String.class))
        .contains("alive")
        .doesNotContain("expired")
        .hasSize(2);
  }

  // Another sign-in's cleanup, or a change ending an account's sessions, may hold some of them.
  @Test
  @DisplayName("the cleanup at sign-in skips expired sessions another transaction has locked")
  void cleanupSkipsLockedSessions() throws Exception {
    long now = System.currentTimeMillis();
    for (String id : new String[] {"locked", "free"}) {
      jdbc.update(
          "INSERT INTO spring_session VALUES (?, ?, ?, ?, 1800, ?, 'someone')",
          String.format("%-36s", id),
          id,
          now - 3_600_000,
          now - 3_600_000,
          now - 1_800_000);
    }
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> holder =
        CompletableFuture.runAsync(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status -> {
                          jdbc.queryForList(
                              "SELECT 1 FROM spring_session WHERE trim(session_id) = 'locked'"
                                  + " FOR UPDATE");
                          locked.countDown();
                          try {
                            release.await(10, TimeUnit.SECONDS);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                        }));
    assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

    try {
      CompletableFuture<HttpResponse<String>> login =
          CompletableFuture.supplyAsync(() -> http.login("owner", "correct-horse", Map.of()));
      assertThat(login.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    } finally {
      release.countDown();
      holder.get(10, TimeUnit.SECONDS);
    }
    assertThat(jdbc.queryForList("SELECT trim(session_id) FROM spring_session", String.class))
        .contains("locked")
        .doesNotContain("free");
  }

  @Test
  @DisplayName("an account with a name as long as allowed can sign in")
  void longestUsernameSignsIn() {
    String name = "a".repeat(255);
    users.save(new User(name, passwordEncoder.encode("correct-horse"), User.ROLE_USER));

    assertThat(http.login(name, "correct-horse", Map.of()).statusCode()).isEqualTo(200);
    assertThat(jdbc.queryForList("SELECT principal_name FROM spring_session", String.class))
        .containsExactly(name);
  }

  @Test
  @DisplayName("refused requests, failed sign-ins and share links start no session")
  void anonymousRequestsStartNoSession() throws IOException {
    Files.writeString(localStore.path(storagePaths.home(owner).key()).resolve("a.txt"), "hi");
    String session =
        HttpTestClient.cookieValue(http.login("owner", "correct-horse", Map.of()), "JSESSIONID");
    HttpResponse<String> share =
        http.postForm(
            "/api/share",
            Map.of("path", "a.txt"),
            http.withCsrf(Map.of("Cookie", "JSESSIONID=" + session)));
    String link = share.body().replaceAll("(?s).*\"url\":\"[^\"]*?(/share/[^\"]+)\".*", "$1");
    int signedIn = sessions();

    assertThat(http.get("/api/me", Map.of()).statusCode()).isEqualTo(401);
    assertThat(http.get("/api/files", Map.of()).statusCode()).isEqualTo(401);
    assertThat(http.login("owner", "wrong", Map.of()).statusCode()).isEqualTo(401);
    HttpResponse<String> download = http.get(link, Map.of());
    assertThat(download.statusCode()).isEqualTo(200);
    assertThat(download.body()).isEqualTo("hi");

    assertThat(sessions()).isEqualTo(signedIn);
  }

  private int sessions() {
    return jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class);
  }
}
