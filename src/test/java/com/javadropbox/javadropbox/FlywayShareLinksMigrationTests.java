package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The share_links table's constraints are what bind a link to its file, so check them on Postgres.
 */
@Testcontainers
@DisplayName("Flyway - V6 share links")
class FlywayShareLinksMigrationTests {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(PostgresTestSupport.IMAGE);

  @BeforeAll
  static void migrate() throws SQLException {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    execute(
        "INSERT INTO users (id, username, password, role) VALUES (1, 'ada', 'h', 'ROLE_ADMIN')");
  }

  @Test
  @DisplayName("deleting a file's row deletes its links")
  void linksGoWithTheirFile() throws SQLException {
    execute(
        "INSERT INTO file_metadata (id, path, filename, is_directory, user_id) VALUES"
            + " (1, 'a.txt', 'a.txt', false, 1)");
    execute(insertLink(1, "hash-1", 1));

    execute("DELETE FROM file_metadata WHERE id = 1");

    assertThat(count("SELECT count(*) FROM share_links WHERE id = 1")).isZero();
  }

  @Test
  @DisplayName("two links cannot share a token hash")
  void tokenHashesAreUnique() throws SQLException {
    execute(
        "INSERT INTO file_metadata (id, path, filename, is_directory, user_id) VALUES"
            + " (2, 'b.txt', 'b.txt', false, 1)");
    execute(insertLink(2, "hash-2", 2));

    assertThatThrownBy(() -> execute(insertLink(3, "hash-2", 2)))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_share_links_token_hash");
  }

  private static String insertLink(long id, String tokenHash, long fileId) {
    return "INSERT INTO share_links (id, token_hash, file_id, path, created_at, expires_at) VALUES ("
        + id
        + ", '"
        + tokenHash
        + "', "
        + fileId
        + ", 'x', now(), now() + interval '1 day')";
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

  private static long count(String sql) throws SQLException {
    try (Connection c = connect();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
