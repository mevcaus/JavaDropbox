package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.TimeZone;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V3 converts local times using the JVM's default zone, so it runs here under several: CI runs in
 * UTC, where every conversion is a no-op and any mistake goes unnoticed.
 */
@Testcontainers
@DisplayName("Flyway - V3 timestamps with time zone")
class FlywayTimestampMigrationTests {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(PostgresTestSupport.IMAGE);

  private static final LocalDateTime WRITTEN = LocalDateTime.of(2026, 1, 15, 9, 30);

  @ParameterizedTest(name = "in {0}")
  @ValueSource(strings = {"UTC", "Europe/Paris", "America/New_York", "GMT+01:00", "GMT-05:30"})
  @DisplayName("an existing local time becomes the instant it meant in the server's zone")
  void localTimesKeepTheirMeaning(String zone) throws SQLException {
    assertThat(migratedInZone(zone)).isEqualTo(WRITTEN.atZone(ZoneId.of(zone)).toInstant());
  }

  @Test
  @DisplayName("an offset-style zone is not read with its sign inverted, as POSIX zones are")
  void offsetStyleZoneKeepsItsSign() throws SQLException {
    // What -Duser.timezone=GMT+1 gives.
    assertThat(migratedInZone("GMT+01:00")).isEqualTo(Instant.parse("2026-01-15T08:30:00Z"));
  }

  @Test
  @DisplayName("Flyway records no checksum for V3, so correcting it keeps old databases valid")
  void noChecksumIsRecorded() throws SQLException {
    migratedInZone("UTC");

    try (Connection c = connect();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery("SELECT checksum FROM flyway_schema_history WHERE version = '3'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getObject(1)).isNull();
    }
  }

  /** Migrates a fresh database holding {@link #WRITTEN} to V3 with the JVM in {@code zone}. */
  private static Instant migratedInZone(String zone) throws SQLException {
    flyway(null).clean();
    flyway("2").migrate();
    execute(
        "INSERT INTO file_metadata (id, path, filename, is_directory, created_at) VALUES"
            + " (1, 'a.txt', 'a.txt', false, '"
            + WRITTEN
            + "')");

    TimeZone original = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zone)));
    try {
      assertThat(ZoneId.systemDefault().getId()).isEqualTo(zone);
      flyway("3").migrate();
    } finally {
      TimeZone.setDefault(original);
    }

    try (Connection c = connect();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT created_at FROM file_metadata WHERE id = 1")) {
      rs.next();
      return rs.getObject(1, OffsetDateTime.class).toInstant();
    }
  }

  private static Flyway flyway(String target) {
    var config =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .cleanDisabled(false);
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
