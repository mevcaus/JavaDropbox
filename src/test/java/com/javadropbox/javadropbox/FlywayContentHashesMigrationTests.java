package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Flyway - V13 content hashes")
class FlywayContentHashesMigrationTests {

  private static final Database DATABASE = PostgresTestSupport.newDatabase();

  @BeforeAll
  static void migrateFilesAndVersions() throws SQLException {
    DATABASE.migrateTo("12");
    DATABASE.execute(
        "INSERT INTO users (username, password, role) VALUES ('ada', 'h', 'ROLE_ADMIN')");
    DATABASE.execute(
        "INSERT INTO file_metadata (path, filename, size, is_directory, current_version, user_id)"
            + " SELECT 'a.txt', 'a.txt', 5, false, 2, id FROM users");
    DATABASE.execute(
        "INSERT INTO file_versions (file_id, version, stored_filename, size)"
            + " SELECT id, 1, id || '/v1', 3 FROM file_metadata");
    DATABASE.migrateTo("latest");
  }

  @Test
  @DisplayName("files and versions from before have no hash, and keep everything else")
  void existingRowsHaveNoHash() throws SQLException {
    assertThat(
            DATABASE.column(
                "SELECT concat_ws(',', path, size, current_version, coalesce(content_hash, '-'))"
                    + " FROM file_metadata"))
        .containsExactly("a.txt,5,2,-");
    assertThat(
            DATABASE.column(
                "SELECT concat_ws(',', version, size, coalesce(content_hash, '-'))"
                    + " FROM file_versions"))
        .containsExactly("1,3,-");
  }
}
