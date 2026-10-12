package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V4 adds a unique version number per file, which databases written by the old code can already
 * violate; it has to clean those rows up rather than fail. V5 indexes the history's link to a file.
 */
@DisplayName("Flyway - V4 unique versions and V5 history index")
class FlywayFileVersionMigrationTests {

  private static final Database DATABASE = PostgresTestSupport.newDatabase();

  @BeforeAll
  static void migrateDatabaseWithDuplicateVersions() throws SQLException {
    DATABASE.migrateTo("3");
    DATABASE.execute(
        "INSERT INTO users (id, username, password, role) VALUES (1, 'ada', 'h', 'ROLE_ADMIN')");
    DATABASE.execute(
        "INSERT INTO file_metadata (id, path, filename, is_directory, user_id) VALUES"
            + " (1, 'r.txt', 'r.txt', false, 1), (2, 's.txt', 's.txt', false, 1)");
    // Two concurrent replaces of r.txt both archived as version 3.
    DATABASE.execute(
        "INSERT INTO file_versions (id, file_id, version, stored_filename) VALUES"
            + " (10, 1, 2, '1/v2'), (11, 1, 3, '1/v3'), (12, 1, 3, '1/v3'), (13, 2, 3, '2/v3')");
    DATABASE.migrateTo("latest");
  }

  @Test
  @DisplayName("keeps the newest row of each duplicated version and leaves the rest alone")
  void duplicateVersionsAreCollapsed() throws SQLException {
    assertThat(DATABASE.column("SELECT id FROM file_versions ORDER BY id"))
        .containsExactly("10", "12", "13");
  }

  @Test
  @DisplayName("afterwards a file can only have one row per version number")
  void versionNumbersAreUnique() {
    assertThatThrownBy(
            () ->
                DATABASE.execute(
                    "INSERT INTO file_versions (id, file_id, version, stored_filename) VALUES"
                        + " (20, 1, 2, '1/v2')"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_file_versions_file_version");
  }

  @Test
  @DisplayName("deleting a file row finds its history through an index, not a table scan")
  void historyIsIndexedByFile() throws SQLException {
    assertThat(DATABASE.column("SELECT indexdef FROM pg_indexes WHERE tablename = 'file_history'"))
        .anyMatch(definition -> definition.endsWith("(file_id)"));

    // The ON DELETE SET NULL action runs this for every deleted file row. The table is tiny here,
    // so rule out sequential scans to see whether an index could serve it at all.
    List<String> plan = new ArrayList<>();
    try (Connection c = DATABASE.connect();
        Statement s = c.createStatement()) {
      s.execute("SET enable_seqscan = off");
      try (ResultSet rs =
          s.executeQuery("EXPLAIN UPDATE file_history SET file_id = NULL WHERE file_id = 1")) {
        while (rs.next()) {
          plan.add(rs.getString(1));
        }
      }
    }
    assertThat(plan).noneMatch(line -> line.contains("Seq Scan"));
  }
}
