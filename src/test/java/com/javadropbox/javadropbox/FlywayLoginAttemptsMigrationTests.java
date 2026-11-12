package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Flyway - V15 login_attempts")
class FlywayLoginAttemptsMigrationTests {

  private static final Database DATABASE = PostgresTestSupport.newDatabase();

  @BeforeAll
  static void migrateAnInstallFromV14() throws SQLException {
    DATABASE.migrateTo("14");
    DATABASE.execute(
        "INSERT INTO users (id, username, password, role) VALUES (1, 'ada', 'h', 'ROLE_ADMIN')");
    DATABASE.migrateTo("latest");
  }

  @Test
  @DisplayName("an upgraded install starts with no failures counted")
  void startsEmpty() throws SQLException {
    assertThat(DATABASE.column("SELECT client FROM login_attempts")).isEmpty();
  }

  @Test
  @DisplayName(
      "an address has one row per kind, there are two kinds, and counts can't go below zero")
  void oneRowPerKind() throws SQLException {
    String row =
        "INSERT INTO login_attempts (kind, client, failures, in_progress, updated_at)"
            + " VALUES ('%s', '203.0.113.7', %d, 0, now())";
    DATABASE.execute(row.formatted("sign-in", 1));
    DATABASE.execute(row.formatted("setup-code", 1));

    assertThatThrownBy(() -> DATABASE.execute(row.formatted("sign-in", 2)))
        .isInstanceOf(SQLException.class);
    assertThatThrownBy(
            () -> DATABASE.execute(row.formatted("other", 1).replace("203.0.113.7", "x")))
        .isInstanceOf(SQLException.class);
    assertThatThrownBy(
            () -> DATABASE.execute(row.formatted("sign-in", -1).replace("203.0.113.7", "x")))
        .isInstanceOf(SQLException.class);
    DATABASE.execute("DELETE FROM login_attempts");
  }

  @Test
  @DisplayName("the accounts from before are kept")
  void rowsAreKept() throws SQLException {
    assertThat(DATABASE.column("SELECT username FROM users")).containsExactly("ada");
  }
}
