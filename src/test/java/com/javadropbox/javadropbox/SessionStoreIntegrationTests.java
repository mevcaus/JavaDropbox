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
    String session =
        HttpTestClient.cookieValue(http.login("owner", "correct-horse", Map.of()), "JSESSIONID");
    assertThat(jdbc.queryForList("SELECT principal_name FROM spring_session", String.class))
        .containsExactly("owner");

    http.postForm("/logout", Map.of(), http.withCsrf(Map.of("Cookie", "JSESSIONID=" + session)));
    assertThat(sessions()).isZero();
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
