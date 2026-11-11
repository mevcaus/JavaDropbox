package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Flyway - V12 used_bytes")
class FlywayUsedBytesMigrationTests {

  private static final Database DATABASE = PostgresTestSupport.newDatabase();

  @BeforeAll
  static void migrateAccountsWithFiles() throws SQLException {
    DATABASE.migrateTo("11");
    DATABASE.execute(
        "INSERT INTO users (username, password, role) VALUES"
            + " ('ada', 'h', 'ROLE_ADMIN'), ('bob', 'h', 'ROLE_USER'), ('cy', 'h', 'ROLE_USER')");
    // Ada: two files, a folder, and a file from an old release whose size was never recorded.
    // Bob: one file with two previous versions. Cy: nothing.
    DATABASE.execute(
        "INSERT INTO file_metadata (path, filename, size, is_directory, current_version, user_id)"
            + " SELECT p, f, s, d, 1, (SELECT id FROM users WHERE username = u) FROM (VALUES"
            + " ('a.txt', 'a.txt', 100, false, 'ada'), ('Docs', 'Docs', 0, true, 'ada'),"
            + " ('Docs/b.txt', 'b.txt', 50, false, 'ada'), ('old.txt', 'old.txt', NULL, false, 'ada'),"
            + " ('c.txt', 'c.txt', 7, false, 'bob')) AS t (p, f, s, d, u)");
    DATABASE.execute(
        "INSERT INTO file_versions (file_id, version, stored_filename, size)"
            + " SELECT id, v, id || '/v' || v, s FROM file_metadata,"
            + " (VALUES (1, 20), (2, 30)) AS t (v, s) WHERE path = 'c.txt'");
    DATABASE.migrateTo("latest");
  }

  @Test
  @DisplayName("each account starts from what its files and their versions add up to")
  void usedBytesAreBackfilled() throws SQLException {
    assertThat(DATABASE.column("SELECT username || ':' || used_bytes FROM users ORDER BY username"))
        .containsExactly("ada:150", "bob:57", "cy:0");
  }
}
