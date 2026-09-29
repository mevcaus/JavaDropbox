package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("Flyway - V3 timestamps with time zone")
class FlywayTimestampMigrationTests {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(PostgresTestSupport.IMAGE);

  @Test
  @DisplayName("an existing local time becomes the instant it meant in the server's zone")
  void localTimesKeepTheirMeaning() throws SQLException {
    flyway("2").migrate();
    LocalDateTime written = LocalDateTime.of(2026, 1, 15, 9, 30);
    execute(
        "INSERT INTO file_metadata (id, path, filename, is_directory, created_at) VALUES"
            + " (1, 'a.txt', 'a.txt', false, '"
            + written
            + "')");

    flyway(null).migrate();

    try (Connection c = connect();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT created_at FROM file_metadata WHERE id = 1")) {
      rs.next();
      assertThat(rs.getObject(1, OffsetDateTime.class).toInstant())
          .isEqualTo(written.atZone(ZoneId.systemDefault()).toInstant());
    }
  }

  private static Flyway flyway(String target) {
    var config =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
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
}
