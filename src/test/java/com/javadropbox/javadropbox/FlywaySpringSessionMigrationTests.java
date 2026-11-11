package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** V9 adds Spring Session's tables to an existing database and leaves everything else alone. */
@DisplayName("Flyway - V9 Spring Session tables")
class FlywaySpringSessionMigrationTests {

  private static final Database DATABASE = PostgresTestSupport.newDatabase();

  @BeforeAll
  static void migrateDatabaseWithAnAccount() throws SQLException {
    DATABASE.migrateTo("8");
    DATABASE.execute(
        "INSERT INTO users (id, username, password, role) VALUES (1, 'ada', 'h', 'ROLE_ADMIN')");
    DATABASE.migrateTo("latest");
  }

  @Test
  @DisplayName("the session tables exist, empty")
  void tablesExist() throws SQLException {
    assertThat(DATABASE.column("SELECT count(*) FROM spring_session")).containsExactly("0");
    assertThat(DATABASE.column("SELECT count(*) FROM spring_session_attributes"))
        .containsExactly("0");
  }

  @Test
  @DisplayName("a session's attributes go with it")
  void attributesCascade() throws SQLException {
    DATABASE.execute(
        "INSERT INTO spring_session VALUES"
            + " ('00000000-0000-0000-0000-000000000001', 's1', 0, 0, 1800, 0, 'ada')");
    DATABASE.execute(
        "INSERT INTO spring_session_attributes VALUES"
            + " ('00000000-0000-0000-0000-000000000001', 'name', '\\x00')");
    DATABASE.execute("DELETE FROM spring_session");
    assertThat(DATABASE.column("SELECT count(*) FROM spring_session_attributes"))
        .containsExactly("0");
  }

  @Test
  @DisplayName("existing accounts are kept")
  void accountsAreKept() throws SQLException {
    assertThat(DATABASE.column("SELECT concat_ws(',', id, username, role) FROM users"))
        .containsExactly("1,ada,ROLE_ADMIN");
  }
}
