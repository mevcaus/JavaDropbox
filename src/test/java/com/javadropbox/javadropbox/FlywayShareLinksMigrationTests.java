package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The share_links table's constraints are what bind a link to its file, so check them on Postgres.
 */
@DisplayName("Flyway - V6 share links")
class FlywayShareLinksMigrationTests {

  private static final Database DATABASE = PostgresTestSupport.newDatabase();

  @BeforeAll
  static void migrate() throws SQLException {
    DATABASE.migrateTo("latest");
    DATABASE.execute(
        "INSERT INTO users (id, username, password, role) VALUES (1, 'ada', 'h', 'ROLE_ADMIN')");
  }

  @Test
  @DisplayName("deleting a file's row deletes its links")
  void linksGoWithTheirFile() throws SQLException {
    DATABASE.execute(
        "INSERT INTO file_metadata (id, path, filename, is_directory, user_id) VALUES"
            + " (1, 'a.txt', 'a.txt', false, 1)");
    DATABASE.execute(insertLink(1, "hash-1", 1));

    DATABASE.execute("DELETE FROM file_metadata WHERE id = 1");

    assertThat(DATABASE.column("SELECT count(*) FROM share_links WHERE id = 1"))
        .containsExactly("0");
  }

  @Test
  @DisplayName("two links cannot share a token hash")
  void tokenHashesAreUnique() throws SQLException {
    DATABASE.execute(
        "INSERT INTO file_metadata (id, path, filename, is_directory, user_id) VALUES"
            + " (2, 'b.txt', 'b.txt', false, 1)");
    DATABASE.execute(insertLink(2, "hash-2", 2));

    assertThatThrownBy(() -> DATABASE.execute(insertLink(3, "hash-2", 2)))
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
}
