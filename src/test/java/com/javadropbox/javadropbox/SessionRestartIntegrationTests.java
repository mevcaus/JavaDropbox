package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Sessions are kept in PostgreSQL, so restarting the app (every deploy and every upgrade) does not
 * sign anyone out. Starts the app twice by hand on one database, as a restart would.
 */
@DisplayName("Sessions across a restart")
class SessionRestartIntegrationTests {

  private final Database database = PostgresTestSupport.newDatabase();

  @TempDir Path servingDirectory;

  @Test
  @DisplayName("a signed-in session still works after the app restarts, until it signs out")
  void sessionSurvivesARestart() throws IOException {
    String session;
    try (ConfigurableApplicationContext app = start()) {
      UserRepository users = app.getBean(UserRepository.class);
      PasswordEncoder encoder = app.getBean(PasswordEncoder.class);
      users.save(new User("ada", encoder.encode("correct-horse"), User.ROLE_ADMIN));

      HttpTestClient http = client(app);
      HttpResponse<String> login = http.login("ada", "correct-horse", Map.of());
      assertThat(login.statusCode()).isEqualTo(200);
      session = HttpTestClient.cookieValue(login, "JSESSIONID");
      assertThat(session).isNotNull();
    }

    try (ConfigurableApplicationContext app = start()) {
      HttpTestClient http = client(app);
      HttpResponse<String> me = http.get("/api/me", Map.of("Cookie", "JSESSIONID=" + session));
      assertThat(me.statusCode()).isEqualTo(200);
      assertThat(me.body()).contains("\"username\":\"ada\"");

      HttpResponse<String> logout =
          http.postForm(
              "/logout", Map.of(), http.withCsrf(Map.of("Cookie", "JSESSIONID=" + session)));
      assertThat(logout.statusCode()).isEqualTo(200);
      assertThat(http.get("/api/me", Map.of("Cookie", "JSESSIONID=" + session)).statusCode())
          .isEqualTo(401);
    }
  }

  private ConfigurableApplicationContext start() throws IOException {
    Files.createDirectories(servingDirectory);
    return new SpringApplicationBuilder(JavadropboxApplication.class)
        .run(
            "--server.port=0",
            "--spring.datasource.url=" + database.url(),
            "--spring.datasource.username=" + database.username(),
            "--spring.datasource.password=" + database.password(),
            "--javadropbox.serving.directory=" + servingDirectory);
  }

  private static HttpTestClient client(ConfigurableApplicationContext app) {
    return new HttpTestClient(
        Integer.parseInt(app.getEnvironment().getRequiredProperty("local.server.port")));
  }
}
