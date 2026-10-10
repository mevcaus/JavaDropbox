package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.service.LocalFileStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where files are stored, as {@code javadropbox.storage.type} says: {@code local} (the default),
 * the serving directory on the server's disk.
 */
@Configuration
public class StorageConfig {

  static final String TYPE = "javadropbox.storage.type";

  private static final Set<String> TYPES = Set.of("local");

  // Checked here, rather than leaving the app to fail for want of a store, so the error says why.
  public StorageConfig(@Value("${" + TYPE + ":local}") String type) {
    if (!TYPES.contains(type.trim().toLowerCase(Locale.ROOT))) {
      throw new IllegalStateException(TYPE + " must be local, not \"" + type + "\"");
    }
  }

  @Bean
  @ConditionalOnProperty(name = TYPE, havingValue = "local", matchIfMissing = true)
  public LocalFileStore localFileStore(@Value("${javadropbox.serving.directory}") String directory)
      throws IOException {
    return new LocalFileStore(Path.of(directory));
  }
}
