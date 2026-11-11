package com.javadropbox.javadropbox.config;

import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Which search index the app uses, as {@code javadropbox.search.backend} says: {@code lucene} (the
 * default), inside the app, or {@code elasticsearch}, a service several app servers can share. Each
 * is a bean of its own, there only when picked.
 */
@Configuration
public class SearchConfig {

  public static final String BACKEND = "javadropbox.search.backend";

  private static final Set<String> BACKENDS = Set.of("lucene", "elasticsearch");

  // Checked here, rather than leaving the app to fail for want of an index, so the error says why.
  public SearchConfig(@Value("${" + BACKEND + ":lucene}") String backend) {
    if (!BACKENDS.contains(backend.toLowerCase(Locale.ROOT))) {
      throw new IllegalStateException(
          BACKEND + " must be lucene or elasticsearch, not \"" + backend + "\"");
    }
  }
}
