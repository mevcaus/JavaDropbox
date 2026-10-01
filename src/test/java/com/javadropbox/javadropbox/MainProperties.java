package com.javadropbox.javadropbox;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Copies settings from the production application.properties into a test context.
 * src/test/resources/application.properties shadows the main file entirely, so without this a test
 * of, say, the forwarded-header handling would run on Spring Boot's defaults rather than on what a
 * real install uses.
 */
final class MainProperties {

  private static final Path FILE = Path.of("src/main/resources/application.properties");

  private static final Properties PROPERTIES = load();

  private MainProperties() {}

  /** Registers each of {@code keys} with its production value; fails if one is not set there. */
  static void register(DynamicPropertyRegistry registry, String... keys) {
    for (String key : keys) {
      String value = PROPERTIES.getProperty(key);
      if (value == null) {
        throw new IllegalStateException(key + " is not set in " + FILE);
      }
      registry.add(key, () -> value);
    }
  }

  private static Properties load() {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(FILE)) {
      properties.load(reader);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + FILE.toAbsolutePath(), e);
    }
    return properties;
  }
}
