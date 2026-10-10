package com.javadropbox.javadropbox;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The PostgreSQL the tests run on. Every Spring context gets a new, empty database of its own in a
 * container the whole test run shares (see {@link TestDatabaseEnvironment}); a suite that needs a
 * server of its own, such as a migration test that starts from an old schema, starts its own
 * container and points its context at it with {@link #register}.
 */
final class PostgresTestSupport {

  // The same major version as compose.yaml and production.
  static final String IMAGE = "postgres:15";

  // src/test/resources/application.properties shadows the main one entirely,
  // so read the production file directly for its schema-management settings.
  private static final Properties MAIN_PROPERTIES =
      load(Path.of("src/main/resources/application.properties"));

  // Started for the first application the tests start, and left running for
  // the rest of the run: Testcontainers removes it when the JVM exits. A
  // database each rather than a container each costs a CREATE DATABASE, a few
  // milliseconds, instead of starting a server.
  private static PostgreSQLContainer<?> shared;
  private static int databasesCreated;

  private PostgresTestSupport() {}

  /** Points a context at {@code postgres} rather than at a database in the shared container. */
  static void register(DynamicPropertyRegistry registry, PostgreSQLContainer<?> postgres) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  /**
   * Production's Flyway and ddl-auto settings, as they are, so the tests break if those settings
   * change in a way that would break a real install.
   */
  static Map<String, Object> schemaSettings() {
    return MAIN_PROPERTIES.stringPropertyNames().stream()
        .filter(k -> k.startsWith("spring.flyway.") || k.equals("spring.jpa.hibernate.ddl-auto"))
        .collect(Collectors.toMap(k -> k, MAIN_PROPERTIES::getProperty));
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

  record Database(String url, String username, String password) {}

  private static Properties load(Path path) {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path)) {
      properties.load(reader);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + path.toAbsolutePath(), e);
    }
    return properties;
  }
}
