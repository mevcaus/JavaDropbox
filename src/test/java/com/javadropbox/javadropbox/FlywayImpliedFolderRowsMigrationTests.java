package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Flyway - V11 rows for folders that had none")
class FlywayImpliedFolderRowsMigrationTests {

  private static final Database DATABASE = PostgresTestSupport.newDatabase();

  @BeforeAll
  static void migrateFoldersMadeOnTheWay() throws SQLException {
    DATABASE.migrateTo("10");
    DATABASE.execute(
        "INSERT INTO users (id, username, password, role) VALUES"
            + " (1, 'ada', 'h', 'ROLE_ADMIN'), (2, 'bob', 'h', 'ROLE_USER')");
    // Ada uploaded into Work/2024 and Work before folders had rows; Bob made his Work folder
    // himself, so it has one, and has a file at the top.
    DATABASE.execute(
        "INSERT INTO file_metadata (path, filename, size, is_directory, current_version,"
            + " user_id, created_at, updated_at) VALUES"
            + " ('Work/2024/report.txt', 'report.txt', 5, false, 2, 1,"
            + " '2024-01-01T00:00:00Z', '2024-03-01T00:00:00Z'),"
            + " ('Work/notes.txt', 'notes.txt', 3, false, 1, 1,"
            + " '2024-02-01T00:00:00Z', '2024-02-01T00:00:00Z'),"
            + " ('Work', 'Work', 0, true, 1, 2, '2023-01-01T00:00:00Z', '2023-01-01T00:00:00Z'),"
            + " ('Work/a_b/x.txt', 'x.txt', 1, false, 1, 2,"
            + " '2023-05-01T00:00:00Z', '2023-05-01T00:00:00Z'),"
            + " ('top.txt', 'top.txt', 1, false, 1, 2,"
            + " '2023-01-01T00:00:00Z', '2023-01-01T00:00:00Z')");
    // Left by older releases: paths with an empty segment, a hidden name, and a file row with
    // rows below it.
    DATABASE.execute(
        "INSERT INTO file_metadata (path, filename, size, is_directory, current_version,"
            + " user_id) VALUES"
            + " ('/Lead/a.txt', 'a.txt', 1, false, 1, 2), ('Dbl//y.txt', 'y.txt', 1, false, 1, 2),"
            + " ('Trail/', 'Trail', 0, true, 1, 2), ('.git/HEAD', 'HEAD', 1, false, 1, 2),"
            + " ('Odd', 'Odd', 1, false, 1, 2), ('Odd/inside.txt', 'inside.txt', 1, false, 1, 2)");
    DATABASE.migrateTo("latest");
  }

  @Test
  @DisplayName("every folder rows imply gets a row of its own, owned by the same account")
  void impliedFoldersGetRows() throws SQLException {
    assertThat(
            DATABASE.column(
                "SELECT concat_ws(',', user_id, path, filename, is_directory, size,"
                    + " current_version) FROM file_metadata WHERE id > 11 ORDER BY user_id, path"))
        .containsExactly("1,Work,Work,t,0,1", "1,Work/2024,2024,t,0,1", "2,Work/a_b,a_b,t,0,1");
  }

  @Test
  @DisplayName("paths the app wouldn't accept imply no folders, and a file row keeps its place")
  void oddPathsImplyNothing() throws SQLException {
    assertThat(
            DATABASE.column(
                "SELECT path FROM file_metadata WHERE path IN ('', '/Lead', 'Dbl', 'Dbl/', '.git')"))
        .isEmpty();
    assertThat(
            DATABASE.column(
                "SELECT concat_ws(',', path, is_directory) FROM file_metadata"
                    + " WHERE id BETWEEN 6 AND 11 ORDER BY id"))
        .containsExactly(
            "/Lead/a.txt,f",
            "Dbl//y.txt,f",
            "Trail/,t",
            ".git/HEAD,f",
            "Odd,f",
            "Odd/inside.txt,f");
  }

  @Test
  @DisplayName("a new folder row is dated from what it holds")
  void newRowsAreDatedFromTheirContents() throws SQLException {
    assertThat(
            DATABASE.column(
                "SELECT concat_ws(',', created_at AT TIME ZONE 'UTC', updated_at AT TIME ZONE"
                    + " 'UTC') FROM file_metadata WHERE user_id = 1 AND path = 'Work'"))
        .containsExactly("2024-01-01 00:00:00,2024-03-01 00:00:00");
  }

  @Test
  @DisplayName("rows that were there are kept as they were")
  void existingRowsAreKept() throws SQLException {
    assertThat(
            DATABASE.column(
                "SELECT concat_ws(',', id, user_id, path, created_at AT TIME ZONE 'UTC')"
                    + " FROM file_metadata WHERE id <= 5 ORDER BY id"))
        .containsExactly(
            "1,1,Work/2024/report.txt,2024-01-01 00:00:00",
            "2,1,Work/notes.txt,2024-02-01 00:00:00",
            "3,2,Work,2023-01-01 00:00:00",
            "4,2,Work/a_b/x.txt,2023-05-01 00:00:00",
            "5,2,top.txt,2023-01-01 00:00:00");
  }
}
