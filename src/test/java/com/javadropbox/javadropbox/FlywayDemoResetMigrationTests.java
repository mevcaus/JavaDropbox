package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Flyway - V17 demo_reset")
class FlywayDemoResetMigrationTests {

  private static final Database DATABASE = PostgresTestSupport.newDatabase();

  @BeforeAll
  static void migrateAnInstallFromV16() throws SQLException {
    DATABASE.migrateTo("16");
    DATABASE.execute(
        "INSERT INTO users (id, username, password, role) VALUES (1, 'demo', 'h', 'ROLE_USER')");
    DATABASE.migrateTo("latest");
  }

  @Test
  @DisplayName("an upgraded install has no reset recorded, so the demo reads the old file's time")
  void startsEmpty() throws SQLException {
    assertThat(DATABASE.column("SELECT reset_at FROM demo_reset")).isEmpty();
  }

  @Test
  @DisplayName("there is only ever one reset time")
  void oneTime() throws SQLException {
    DATABASE.execute("INSERT INTO demo_reset (reset_at) VALUES (now())");

    assertThatThrownBy(() -> DATABASE.execute("INSERT INTO demo_reset (reset_at) VALUES (now())"))
        .isInstanceOf(SQLException.class);
    assertThatThrownBy(
            () -> DATABASE.execute("INSERT INTO demo_reset (id, reset_at) VALUES (2, now())"))
        .isInstanceOf(SQLException.class);
    DATABASE.execute("DELETE FROM demo_reset");
  }

  @Test
  @DisplayName("the accounts from before are kept")
  void rowsAreKept() throws SQLException {
    assertThat(DATABASE.column("SELECT username FROM users")).containsExactly("demo");
  }
}
