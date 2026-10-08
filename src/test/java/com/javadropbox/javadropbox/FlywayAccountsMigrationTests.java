package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V7 gives every account its own files: what was stored in the one shared space becomes the first
 * account's, that account becomes an admin, and paths are unique per account rather than overall.
 * Invitations and password resets get a table of their own.
 */
@Testcontainers
@DisplayName("Flyway - V7 accounts of their own")
class FlywayAccountsMigrationTests {

  private static final String LINK_COLUMNS =
      "INSERT INTO account_links (id, token_hash, purpose, username, role, user_id, created_at,"
          + " expires_at) VALUES ";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(PostgresTestSupport.IMAGE);

  @BeforeAll
  static void migrateSharedSpace() throws SQLException {
    flyway("6", "public").migrate();
    // An install from before the setup code, whose owner has ROLE_USER, and a second account
    // made by hand that has no role and owns some of the rows.
    execute(
        "INSERT INTO users (id, username, password, role) VALUES"
            + " (1, 'owner', 'h', 'ROLE_USER'), (2, 'helper', 'h', NULL)");
    execute(
        "INSERT INTO file_metadata (id, path, filename, is_directory, user_id) VALUES"
            + " (10, 'a.txt', 'a.txt', false, 1), (11, 'b.txt', 'b.txt', false, 2),"
            + " (12, 'c.txt', 'c.txt', false, NULL)");
    execute(
        "INSERT INTO file_history (id, file_id, change_type, success, user_id) VALUES"
            + " (20, 10, 'UPLOAD', true, 1), (21, 11, 'UPLOAD', true, 2),"
            + " (22, NULL, 'DELETE', true, NULL)");
    flyway(null, "public").migrate();
  }

  @Test
  @DisplayName("the first account becomes an admin, and the others users who can sign in")
  void firstAccountIsTheAdmin() throws SQLException {
    assertThat(column("SELECT id || ':' || role || ':' || enabled FROM users ORDER BY id"))
        .containsExactly("1:ROLE_ADMIN:true", "2:ROLE_USER:true");
  }

  @Test
  @DisplayName("every file and history entry belongs to the first account, whoever made it")
  void everythingBelongsToTheFirstAccount() throws SQLException {
    assertThat(column("SELECT id || ':' || user_id FROM file_metadata WHERE id < 30 ORDER BY id"))
        .containsExactly("10:1", "11:1", "12:1");
    assertThat(column("SELECT DISTINCT user_id FROM file_history")).containsExactly("1");
  }

  @Test
  @DisplayName("two accounts can each have a path, but one account cannot have it twice")
  void pathsAreUniquePerAccount() throws SQLException {
    execute(
        "INSERT INTO file_metadata (id, path, filename, is_directory, user_id) VALUES"
            + " (30, 'a.txt', 'a.txt', false, 2)");

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO file_metadata (id, path, filename, is_directory, user_id)"
                        + " VALUES (31, 'a.txt', 'a.txt', false, 1)"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_file_metadata_owner_path");
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO file_metadata (id, path, filename, is_directory) VALUES"
                        + " (32, 'z.txt', 'z.txt', false)"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("user_id");
  }

  @Test
  @DisplayName("a role is ADMIN or USER, and a quota is more than nothing")
  void rolesAndQuotasAreChecked() {
    assertThatThrownBy(() -> execute("UPDATE users SET role = 'ROLE_ROOT' WHERE id = 2"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_users_role");
    assertThatThrownBy(() -> execute("UPDATE users SET quota_bytes = 0 WHERE id = 2"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_users_quota_bytes");
  }

  @Test
  @DisplayName("one open invitation per username and one reset per account, each saying whose")
  void accountLinksAreChecked() throws SQLException {
    execute(LINK_COLUMNS + "(1, 'h1', 'INVITE', 'carol', 'ROLE_USER', NULL, now(), now())");
    execute(LINK_COLUMNS + "(2, 'h2', 'PASSWORD_RESET', NULL, NULL, 2, now(), now())");

    assertThatThrownBy(
            () ->
                execute(
                    LINK_COLUMNS + "(3, 'h3', 'INVITE', 'carol', 'ROLE_USER', NULL, now(), now())"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_account_links_invite");
    assertThatThrownBy(
            () ->
                execute(LINK_COLUMNS + "(4, 'h4', 'PASSWORD_RESET', NULL, NULL, 2, now(), now())"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_account_links_reset");
    assertThatThrownBy(
            () ->
                execute(
                    LINK_COLUMNS + "(5, 'h5', 'INVITE', NULL, 'ROLE_USER', NULL, now(), now())"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_account_links_purpose");
  }

  @Test
  @DisplayName("with no account at all, rows there is nobody to give are dropped")
  void rowsWithoutAnAccountAreDropped() throws SQLException {
    String schema = "no_accounts";
    flyway("6", schema).migrate();
    execute(
        "INSERT INTO "
            + schema
            + ".file_metadata (id, path, filename, is_directory) VALUES (1, 'a', 'a', true)");
    execute(
        "INSERT INTO "
            + schema
            + ".file_versions (file_id, version, stored_filename) VALUES (1, 1, '1/v1')");

    flyway(null, schema).migrate();

    assertThat(column("SELECT count(*) FROM " + schema + ".file_metadata")).containsExactly("0");
    assertThat(column("SELECT count(*) FROM " + schema + ".file_versions")).containsExactly("0");
  }

  private static Flyway flyway(String target, String schema) {
    var config =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema)
            .locations("classpath:db/migration");
    if (target != null) {
      config.target(target);
    }
    return config.load();
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void execute(String sql) throws SQLException {
    try (Connection c = connect();
        Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  private static List<String> column(String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection c = connect();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      while (rs.next()) {
        values.add(rs.getString(1));
      }
    }
    return values;
  }
}
