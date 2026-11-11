package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Upgrading from the release before the file list came from rows: at V10, with the first account's
 * files already taken in, and two accounts with files that were listed only because the tree walked
 * the store. Every one of them is listed afterwards, with an id.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Upgrading to a file list read from rows")
class UpgradeToListingRowsIntegrationTests {

  private static final Database DATABASE = PostgresTestSupport.newDatabase();

  @TempDir static Path servingDir;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    PostgresTestSupport.register(registry, DATABASE);
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
  }

  @BeforeAll
  static void installFromThePreviousRelease() throws IOException, SQLException {
    DATABASE.migrateTo("10");
    long ada;
    long bob;
    try (Connection c = DATABASE.connect();
        Statement s = c.createStatement()) {
      s.execute(
          "INSERT INTO users (username, password, role) VALUES"
              + " ('ada', 'h', 'ROLE_ADMIN'), ('bob', 'h', 'ROLE_USER')");
      ada = id(s, "ada");
      bob = id(s, "bob");
      s.execute(
          "INSERT INTO one_time_steps (name, completed_at) VALUES"
              + " ('first-account-import', now())");
      // Uploaded through the app: Ada's file has its row; Bob's upload into Work made the folder
      // on the way, before folders got rows of their own.
      s.execute(
          "INSERT INTO file_metadata (path, filename, size, is_directory, current_version,"
              + " user_id, created_at, updated_at) VALUES"
              + " ('uploaded.txt', 'uploaded.txt', 2, false, 1, "
              + ada
              + ", now(), now()),"
              + " ('Work/doc.txt', 'doc.txt', 3, false, 1, "
              + bob
              + ", now(), now())");
    }
    Path adas = Files.createDirectories(servingDir.resolve(".users/" + ada));
    Files.writeString(adas.resolve("uploaded.txt"), "up");
    Files.writeString(adas.resolve("by hand.txt"), "copied in");
    Path bobs = Files.createDirectories(servingDir.resolve(".users/" + bob));
    Files.createDirectories(bobs.resolve("Work"));
    Files.writeString(bobs.resolve("Work/doc.txt"), "doc");
    Files.createDirectories(bobs.resolve("Photos"));
    Files.writeString(bobs.resolve("Photos/beach.jpg"), "jpeg");
    // Put at the top after the first account's files were taken in: still nobody's.
    Files.writeString(servingDir.resolve("stray.txt"), "nobody's");
  }

  @Test
  @DisplayName("the first account's files are listed, the one copied in by hand included")
  void firstAccountKeepsItsFiles() throws Exception {
    mockMvc
        .perform(get("/api/files").with(user("ada")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[?(@.name == 'uploaded.txt')].id").isNotEmpty())
        .andExpect(jsonPath("$[?(@.name == 'by hand.txt')].id").isNotEmpty());
  }

  @Test
  @DisplayName("another account's files are listed too, in folders that have rows now")
  void otherAccountKeepsItsFiles() throws Exception {
    mockMvc
        .perform(get("/api/files").with(user("bob")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[?(@.name == 'Work')].id").isNotEmpty())
        .andExpect(jsonPath("$[?(@.name == 'Photos')].id").isNotEmpty())
        .andExpect(
            jsonPath("$[?(@.name == 'Photos')].children[?(@.name == 'beach.jpg')].id")
                .isNotEmpty());
  }

  @Test
  @DisplayName("what each account stores is counted from its files and versions")
  void usageMatchesRows() {
    TestDatabase.assertUsageMatchesRows(jdbc);
  }

  @Test
  @DisplayName("it is recorded, and what was put at the top later stays there")
  void recordedAndTopLeftAlone() throws Exception {
    assertThat(DATABASE.column("SELECT name FROM one_time_steps"))
        .contains("first-account-import", "every-account-import");
    assertThat(servingDir.resolve("stray.txt")).hasContent("nobody's");
  }

  private static long id(Statement s, String username) throws SQLException {
    try (ResultSet r = s.executeQuery("SELECT id FROM users WHERE username = '" + username + "'")) {
      r.next();
      return r.getLong(1);
    }
  }
}
