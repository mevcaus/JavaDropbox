package com.javadropbox.javadropbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Empties the serving directory between tests, as {@link TestDatabase} empties the database: the
 * context, and so the folder, outlives each test, and the next one should start on an empty store.
 */
final class TestStorage {

  private TestStorage() {}

  /**
   * Deletes everything in {@code servingDir}, which stays. Symlinks are deleted rather than
   * followed, dangling ones included.
   */
  static void empty(Path servingDir) throws IOException {
    try (Stream<Path> walk = Files.walk(servingDir)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        if (!path.equals(servingDir)) {
          Files.delete(path);
        }
      }
    }
  }
}
