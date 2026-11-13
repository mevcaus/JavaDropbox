package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Flyway - V16 setup_code")
class FlywaySetupCodeMigrationTests {

  private static final Database DATABASE = PostgresTestSupport.newDatabase();

  @BeforeAll
  static void migrateAnInstallFromV15() throws SQLException {
    DATABASE.migrateTo("15");
    DATABASE.execute(
        "INSERT INTO users (id, username, password, role) VALUES (1, 'ada', 'h', 'ROLE_ADMIN')");
    DATABASE.migrateTo("latest");
  }

  @Test
  @DisplayName("an upgraded install has no code stored")
  void startsEmpty() throws SQLException {
    assertThat(DATABASE.column("SELECT code FROM setup_code")).isEmpty();
  }

  @Test
  @DisplayName("there is only ever one code")
  void oneCode() throws SQLException {
    DATABASE.execute("INSERT INTO setup_code (code, created_at) VALUES ('ABCDE-FGHJK', now())");

    assertThatThrownBy(
            () ->
                DATABASE.execute(
                    "INSERT INTO setup_code (code, created_at) VALUES ('KLMNP-QRSTU', now())"))
        .isInstanceOf(SQLException.class);
    assertThatThrownBy(
            () ->
                DATABASE.execute(
                    "INSERT INTO setup_code (id, code, created_at) VALUES (2, 'X', now())"))
        .isInstanceOf(SQLException.class);
    DATABASE.execute("DELETE FROM setup_code");
  }

  @Test
  @DisplayName("the accounts from before are kept")
  void rowsAreKept() throws SQLException {
    assertThat(DATABASE.column("SELECT username FROM users")).containsExactly("ada");
  }
}
