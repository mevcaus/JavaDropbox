package com.javadropbox.javadropbox;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/** Points a Spring test context at a Postgres container and runs it the way production does. */
final class PostgresTestSupport {

  // The same major version as compose.yaml and production.
  static final String IMAGE = "postgres:15";

  // src/test/resources/application.properties shadows the main one entirely,
  // so read the production file directly for its schema-management settings.
  private static final Properties MAIN_PROPERTIES =
      load(Path.of("src/main/resources/application.properties"));

  private PostgresTestSupport() {}

  static void register(DynamicPropertyRegistry registry, PostgreSQLContainer<?> postgres) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    // The H2 driver and dialect are pinned in the test application.properties,
    // so both need overriding here too or Hibernate would talk H2 to Postgres.
    registry.add("spring.datasource.driverClassName", postgres::getDriverClassName);
    registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    // The test application.properties disables Flyway and has Hibernate
    // generate the schema, which only suits H2. On Postgres, use production's
    // Flyway and ddl-auto settings as they are, so these tests break if those
    // settings change in a way that would break a real install.
    registry.add("spring.flyway.enabled", () -> "true");
    MAIN_PROPERTIES.stringPropertyNames().stream()
        .filter(k -> k.startsWith("spring.flyway.") || k.equals("spring.jpa.hibernate.ddl-auto"))
        .forEach(k -> registry.add(k, () -> MAIN_PROPERTIES.getProperty(k)));
  }

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
