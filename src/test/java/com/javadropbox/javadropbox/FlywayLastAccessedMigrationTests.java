package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** V8 drops file_metadata.last_accessed, which nothing read, and must keep the rows themselves. */
@DisplayName("Flyway - V8 drop last_accessed")
class FlywayLastAccessedMigrationTests {

  private static final Database DATABASE = PostgresTestSupport.newDatabase();

  @BeforeAll
  static void migrateDatabaseWithAccessTimes() throws SQLException {
    DATABASE.migrateTo("7");
    DATABASE.execute(
        "INSERT INTO users (id, username, password, role) VALUES (1, 'ada', 'h', 'ROLE_ADMIN')");
    DATABASE.execute(
        "INSERT INTO file_metadata"
            + " (id, path, filename, size, is_directory, current_version, user_id, created_at,"
            + " updated_at, last_accessed) VALUES"
            + " (1, 'a.txt', 'a.txt', 5, false, 2, 1, '2026-01-01T00:00:00Z',"
            + " '2026-02-01T00:00:00Z', '2026-03-01T00:00:00Z')");
    DATABASE.migrateTo("latest");
  }

  @Test
  @DisplayName("the column is gone")
  void columnIsDropped() throws SQLException {
    assertThat(
            DATABASE.column(
                "SELECT column_name FROM information_schema.columns"
                    + " WHERE table_schema = current_schema() AND table_name = 'file_metadata'"))
        .isNotEmpty()
        .doesNotContain("last_accessed");
  }

  @Test
  @DisplayName("every row keeps the rest of what it had")
  void rowsAreKept() throws SQLException {
    assertThat(
            DATABASE.column(
                "SELECT concat_ws(',', path, size, current_version, user_id,"
                    + " created_at AT TIME ZONE 'UTC', updated_at AT TIME ZONE 'UTC')"
                    + " FROM file_metadata"))
        .containsExactly("a.txt,5,2,1,2026-01-01 00:00:00,2026-02-01 00:00:00");
  }
}
