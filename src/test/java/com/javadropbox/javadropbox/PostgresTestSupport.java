package com.javadropbox.javadropbox;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The PostgreSQL the tests run on: one container for the whole run, with a new, empty database for
 * each Spring context (see {@link TestDatabaseEnvironment}). A suite that needs its database in
 * some other state first, such as a migration test that starts from an old schema, takes one with
 * {@link #newDatabase} and, if it starts the app, points its context at it with {@link #register}.
 */
final class PostgresTestSupport {

  // The same major version as compose.yaml and production.
  static final String IMAGE = "postgres:15";

  // Started for the first database the tests ask for, and left running for the rest of the run:
  // Testcontainers removes it when the JVM exits. A database each rather than a container each
  // costs a CREATE DATABASE, a few milliseconds, instead of starting a server.
  private static PostgreSQLContainer<?> shared;
  private static int databasesCreated;

  private PostgresTestSupport() {}

  /** Points a context at {@code database} rather than at a new one of its own. */
  static void register(DynamicPropertyRegistry registry, Database database) {
    registry.add("spring.datasource.url", database::url);
    registry.add("spring.datasource.username", database::username);
    registry.add("spring.datasource.password", database::password);
  }

  /** Creates a new, empty database in the shared container, starting it first if need be. */
  static synchronized Database newDatabase() {
    if (shared == null) {
      PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(IMAGE);
      postgres.start();
      shared = postgres;
    }
    String name = "context_" + ++databasesCreated;
    try (Connection connection =
            DriverManager.getConnection(
                shared.getJdbcUrl(), shared.getUsername(), shared.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    } catch (SQLException e) {
      throw new IllegalStateException("could not create the test database " + name, e);
    }
    String url =
        "jdbc:postgresql://"
            + shared.getHost()
            + ":"
            + shared.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
            + "/"
            + name;
    return new Database(url, shared.getUsername(), shared.getPassword());
  }

  /** A database in the shared container, and what tests that work on one directly do with it. */
  record Database(String url, String username, String password) {

    Connection connect() throws SQLException {
      return DriverManager.getConnection(url, username, password);
    }

    void execute(String sql) throws SQLException {
      try (Connection c = connect();
          Statement s = c.createStatement()) {
        s.execute(sql);
      }
    }

    /** The first column of each row {@code sql} returns, as text. */
    List<String> column(String sql) throws SQLException {
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

    /** Flyway with the app's migrations on this database, for a test to configure further. */
    FluentConfiguration flyway() {
      return Flyway.configure()
          .dataSource(url, username, password)
          .locations("classpath:db/migration");
    }

    /** Migrates this database to {@code version}, or to the newest with {@code "latest"}. */
    void migrateTo(String version) {
      flyway().target(version).load().migrate();
    }
  }
}
