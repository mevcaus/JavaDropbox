package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V2 adds unique constraints that databases written by the old code can already violate. It has to
 * clean those rows up rather than fail, or the upgrade would never start on exactly the installs
 * that hit the bugs.
 */
@Testcontainers
@DisplayName("Flyway - V2 integrity constraints on a database with bad data")
class FlywayIntegrityMigrationTests {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(PostgresTestSupport.IMAGE);

  // Rows are inserted with explicit ids, which do not advance the identity sequences, so every
  // insert here names its id. Assertions on shared tables stick to the seeded ids (up to 33).
  @BeforeAll
  static void migrateDatabaseWithBadData() throws SQLException {
    flyway("1").migrate();
    try (Connection c = connect();
        Statement s = c.createStatement()) {
      s.execute(
          "INSERT INTO users (id, username, password, role) VALUES"
              + " (1, 'ada', 'h', 'ROLE_ADMIN'), (2, 'ada', 'h', 'ROLE_ADMIN'),"
              + " (3, 'bob', 'h', 'ROLE_USER')");
      // Path "a" twice: the delete-then-recreate bug. Row 12 predates filename/is_directory
      // being set, row 13 has no path at all.
      s.execute(
          "INSERT INTO file_metadata (id, path, filename, is_directory, user_id) VALUES"
              + " (10, 'a', 'a', true, 2), (11, 'a', 'a', true, 1),"
              + " (12, 'docs/x.txt', NULL, NULL, 1), (13, NULL, 'lost', false, 1)");
      s.execute(
          "INSERT INTO file_versions (id, file_id, version, stored_filename, user_id) VALUES"
              + " (20, 10, 1, 'a.v1', 1), (21, 11, 1, 'a.v2', 2)");
      s.execute(
          "INSERT INTO file_history (id, file_id, change_type, success, error_message, user_id)"
              + " VALUES (30, 10, 'CREATE_FOLDER', true, NULL, 1),"
              + " (31, 11, 'CREATE_FOLDER', true, NULL, 2),"
              + " (32, 12, 'UPLOAD', true, 'Restored from version 1', 1),"
              + " (33, NULL, 'UPLOAD', false, 'disk full', 1)");
    }
    flyway(null).migrate();
  }

  @Test
  @DisplayName("keeps the newest row per path and drops rows without one")
  void duplicatePathsAreCollapsed() throws SQLException {
    assertThat(column("SELECT id FROM file_metadata ORDER BY id")).containsExactly("11", "12");
    assertThat(column("SELECT file_id FROM file_versions")).containsExactly("11");
  }

  @Test
  @DisplayName("keeps the history of dropped rows, detached from them")
  void historyIsKept() throws SQLException {
    assertThat(
            column(
                "SELECT coalesce(file_id::text, '-') FROM file_history WHERE id <= 33 ORDER BY id"))
        .containsExactly("-", "11", "12", "-");
  }

  @Test
  @DisplayName("fills in filename and is_directory for rows missing them")
  void missingColumnsAreFilled() throws SQLException {
    assertThat(column("SELECT filename || ':' || is_directory FROM file_metadata WHERE id = 12"))
        .containsExactly("x.txt:false");
  }

  @Test
  @DisplayName("merges duplicate users into the oldest account")
  void duplicateUsersAreMerged() throws SQLException {
    assertThat(column("SELECT id || ':' || username FROM users ORDER BY id"))
        .containsExactly("1:ada", "3:bob");
    assertThat(column("SELECT DISTINCT user_id FROM file_history WHERE id <= 33"))
        .containsExactly("1");
  }

  @Test
  @DisplayName("moves restore notes out of error_message into a RESTORE entry")
  void restoresAreReclassified() throws SQLException {
    assertThat(
            column(
                "SELECT change_type || '|' || details || '|' || coalesce(error_message, '-')"
                    + " FROM file_history WHERE id = 32"))
        .containsExactly("RESTORE|Restored from version 1|-");
    assertThat(column("SELECT error_message FROM file_history WHERE id = 33"))
        .containsExactly("disk full");
  }

  @Test
  @DisplayName("afterwards a path and a username can only be used once")
  void constraintsHold() {
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO file_metadata (id, path, filename, is_directory) VALUES"
                        + " (98, 'a', 'a', true)"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_file_metadata_path");
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO users (id, username, password, role) VALUES (99, 'bob', 'h', 'r')"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_users_username");
  }

  @Test
  @DisplayName("deleting a file row removes its versions and detaches its history")
  void deleteCascades() throws SQLException {
    execute(
        "INSERT INTO file_metadata (id, path, filename, is_directory) VALUES (50, 'z', 'z', false)");
    execute(
        "INSERT INTO file_versions (file_id, version, stored_filename) VALUES (50, 1, '50/v1')");
    execute(
        "INSERT INTO file_history (id, file_id, change_type, success) VALUES (51, 50, 'UPLOAD', true)");

    execute("DELETE FROM file_metadata WHERE id = 50");

    assertThat(column("SELECT count(*) FROM file_versions WHERE file_id = 50"))
        .containsExactly("0");
    assertThat(column("SELECT coalesce(file_id::text, '-') FROM file_history WHERE id = 51"))
        .containsExactly("-");
  }

  private static Flyway flyway(String target) {
    var config =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration");
    if (target != null) {
      config.target(target);
    }
    return config.load();
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void execute(String sql) throws SQLException {
    try (Connection c = connect();
        Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  private static List<String> column(String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection c = connect();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      while (rs.next()) {
        values.add(rs.getString(1));
      }
    }
    return values;
  }
}
