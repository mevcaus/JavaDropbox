package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Flyway - V10 one_time_steps")
class FlywayOneTimeStepsMigrationTests {

  private static final Database DATABASE = PostgresTestSupport.newDatabase();

  @BeforeAll
  static void migrateAnInstallFromV9() throws SQLException {
    DATABASE.migrateTo("9");
    DATABASE.execute(
        "INSERT INTO users (id, username, password, role) VALUES (1, 'ada', 'h', 'ROLE_ADMIN')");
    DATABASE.execute(
        "INSERT INTO file_metadata (id, path, filename, size, is_directory, current_version,"
            + " user_id) VALUES (1, 'a.txt', 'a.txt', 5, false, 2, 1)");
    DATABASE.migrateTo("latest");
  }

  @Test
  @DisplayName("an upgraded install has no step recorded, so the first start does them")
  void startsEmpty() throws SQLException {
    assertThat(DATABASE.column("SELECT name FROM one_time_steps")).isEmpty();
  }

  @Test
  @DisplayName("a step is recorded once")
  void stepIsRecordedOnce() throws SQLException {
    DATABASE.execute("INSERT INTO one_time_steps (name, completed_at) VALUES ('once', now())");

    assertThatThrownBy(
            () ->
                DATABASE.execute(
                    "INSERT INTO one_time_steps (name, completed_at) VALUES ('once', now())"))
        .isInstanceOf(SQLException.class);
    assertThatThrownBy(
            () -> DATABASE.execute("INSERT INTO one_time_steps (name) VALUES ('no time')"))
        .isInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("the files from before are kept")
  void rowsAreKept() throws SQLException {
    assertThat(DATABASE.column("SELECT concat_ws(',', id, path, user_id) FROM file_metadata"))
        .containsExactly("1,a.txt,1");
  }
}
