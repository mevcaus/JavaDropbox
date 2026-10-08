package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.SearchIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * An install from before accounts had folders of their own, started with this version: its database
 * at V6, and its files and versions at the top of the serving directory, owned by whichever account
 * made them. Afterwards they are all the first account's, in its folder, with their ids, versions
 * and share links intact; the other account starts with nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Upgrading an install from before accounts had their own files")
class UpgradeToAccountsIntegrationTests {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(PostgresTestSupport.IMAGE);

  @TempDir static Path servingDir;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    PostgresTestSupport.register(registry, POSTGRES);
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private SearchIndex searchIndex;
  @Autowired private ObjectMapper json;

  // Before the app starts: the database as V6 left it, and the files where that version kept them.
  @BeforeAll
  static void installFromBeforeAccounts() throws IOException, SQLException {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .target("6")
        .load()
        .migrate();
    try (Connection c =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement s = c.createStatement()) {
      // The owner predates the setup code, so it is ROLE_USER; the helper made the folder.
      s.execute(
          "INSERT INTO users (username, password, role) VALUES"
              + " ('owner', 'h', 'ROLE_USER'), ('helper', 'h', 'ROLE_USER')");
      s.execute(
          "INSERT INTO file_metadata (id, path, filename, is_directory, size, current_version,"
              + " user_id) VALUES"
              + " (10, 'docs', 'docs', true, 0, 1, (SELECT id FROM users WHERE username = 'helper')),"
              + " (11, 'docs/report.txt', 'report.txt', false, 3, 2,"
              + " (SELECT id FROM users WHERE username = 'owner'))");
      s.execute(
          "INSERT INTO file_versions (file_id, version, stored_filename, size) VALUES"
              + " (11, 1, '11/v1', 3)");
      s.execute(
          "INSERT INTO share_links (token_hash, file_id, path, created_at, expires_at) VALUES"
              + " ('"
              + sha256("legacy-token")
              + "', 11, 'docs/report.txt', now(), now() + interval '1 day')");
    }
    Files.createDirectories(servingDir.resolve("docs"));
    Files.writeString(servingDir.resolve("docs/report.txt"), "new");
    Files.writeString(servingDir.resolve("copied in.txt"), "by hand");
    Files.createDirectories(servingDir.resolve(".versions/11"));
    Files.writeString(servingDir.resolve(".versions/11/v1"), "old");
  }

  @Test
  @DisplayName("the files are in the first account's folder, listed with their ids")
  void filesBelongToTheFirstAccount() throws Exception {
    assertThat(servingDir.resolve("docs")).doesNotExist();
    assertThat(servingDir.resolve(".versions/11/v1")).hasContent("old");

    mockMvc
        .perform(get("/api/files").with(user("owner")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("docs"))
        .andExpect(jsonPath("$[0].id").value(10))
        .andExpect(jsonPath("$[0].children[0].id").value(11))
        .andExpect(jsonPath("$[1].name").value("copied in.txt"));
    mockMvc
        .perform(get("/api/files/download").param("path", "docs/report.txt").with(user("owner")))
        .andExpect(content().string("new"));
  }

  @Test
  @DisplayName("the other account starts with nothing")
  void otherAccountHasNothing() throws Exception {
    mockMvc
        .perform(get("/api/files").with(user("helper")))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  @DisplayName("the first account is an admin, and the other a user")
  void firstAccountIsAnAdmin() throws Exception {
    assertThat(users.findByUsername("owner").orElseThrow().getRole()).isEqualTo(User.ROLE_ADMIN);
    assertThat(users.findByUsername("helper").orElseThrow().getRole()).isEqualTo(User.ROLE_USER);

    mockMvc
        .perform(get("/api/admin/users").with(user("owner").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].username").value("helper"))
        .andExpect(jsonPath("$[0].role").value("USER"))
        .andExpect(jsonPath("$[1].username").value("owner"))
        .andExpect(jsonPath("$[1].role").value("ADMIN"));
  }

  @Test
  @DisplayName("search finds the files in the first account's folder, and only for that account")
  void searchFindsTheMovedFiles() throws Exception {
    searchIndex.awaitIdle(Duration.ofSeconds(30));

    // A file the app stored, and one copied in by hand that it never tracked.
    assertThat(searchPaths(user("owner"), "new")).containsExactly("docs/report.txt");
    assertThat(searchPaths(user("owner"), "hand")).containsExactly("copied in.txt");
    assertThat(searchPaths(user("helper"), "new")).isEmpty();
    assertThat(searchPaths(user("helper"), "hand")).isEmpty();
  }

  @Test
  @DisplayName("versions and share links still work")
  void versionsAndLinksSurvive() throws Exception {
    mockMvc
        .perform(get("/share/legacy-token"))
        .andExpect(status().isOk())
        .andExpect(content().string("new"));
    mockMvc
        .perform(get("/api/files/11/versions").with(user("owner")))
        .andExpect(jsonPath("$[0].version").value(1));
    mockMvc
        .perform(
            post("/api/files/11/versions/1/restore")
                .param("mode", "COPY")
                .with(user("owner"))
                .with(csrf()))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/files/download").param("path", "docs/report_v1.txt").with(user("owner")))
        .andExpect(content().string("old"));
  }

  private List<String> searchPaths(RequestPostProcessor as, String q) throws Exception {
    String body =
        mockMvc
            .perform(get("/api/search").param("q", q).with(as))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.indexing").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    List<String> paths = new ArrayList<>();
    json.readTree(body).path("results").forEach(r -> paths.add(r.path("relativePath").asText()));
    return paths;
  }

  // What the share_links table keeps of a link's token.
  private static String sha256(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
