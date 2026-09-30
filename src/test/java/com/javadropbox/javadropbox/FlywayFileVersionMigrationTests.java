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
 * V4 adds a unique version number per file, which databases written by the old code can already
 * violate; it has to clean those rows up rather than fail. V5 indexes the history's link to a file.
 */
@Testcontainers
@DisplayName("Flyway - V4 unique versions and V5 history index")
class FlywayFileVersionMigrationTests {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(PostgresTestSupport.IMAGE);

  @BeforeAll
  static void migrateDatabaseWithDuplicateVersions() throws SQLException {
    flyway("3").migrate();
    execute(
        "INSERT INTO file_metadata (id, path, filename, is_directory) VALUES"
            + " (1, 'r.txt', 'r.txt', false), (2, 's.txt', 's.txt', false)");
    // Two concurrent replaces of r.txt both archived as version 3.
    execute(
        "INSERT INTO file_versions (id, file_id, version, stored_filename) VALUES"
            + " (10, 1, 2, '1/v2'), (11, 1, 3, '1/v3'), (12, 1, 3, '1/v3'), (13, 2, 3, '2/v3')");
    flyway(null).migrate();
  }

  @Test
  @DisplayName("keeps the newest row of each duplicated version and leaves the rest alone")
  void duplicateVersionsAreCollapsed() throws SQLException {
    assertThat(column("SELECT id FROM file_versions ORDER BY id"))
        .containsExactly("10", "12", "13");
  }

  @Test
  @DisplayName("afterwards a file can only have one row per version number")
  void versionNumbersAreUnique() {
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO file_versions (id, file_id, version, stored_filename) VALUES"
                        + " (20, 1, 2, '1/v2')"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_file_versions_file_version");
  }

  @Test
  @DisplayName("deleting a file row finds its history through an index, not a table scan")
  void historyIsIndexedByFile() throws SQLException {
    assertThat(column("SELECT indexdef FROM pg_indexes WHERE tablename = 'file_history'"))
        .anyMatch(definition -> definition.endsWith("(file_id)"));

    // The ON DELETE SET NULL action runs this for every deleted file row. The table is tiny here,
    // so rule out sequential scans to see whether an index could serve it at all.
    List<String> plan = new ArrayList<>();
    try (Connection c = connect();
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
