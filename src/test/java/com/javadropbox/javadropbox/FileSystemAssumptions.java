package com.javadropbox.javadropbox;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Skips a test that needs a particular kind of filesystem. The temporary directory on macOS (APFS)
 * and Windows ignores letter case, CI's Linux does not, so tests of case-variant paths only prove
 * something on the former.
 */
public final class FileSystemAssumptions {

  private FileSystemAssumptions() {}

  /** Skips the test unless names in {@code dir} that differ only in letter case are one entry. */
  public static void assumeCaseInsensitive(Path dir) throws IOException {
    Path probe = Files.createTempFile(dir, "case-probe-", ".tmp");
    try {
      Path upperCase =
          probe.resolveSibling(probe.getFileName().toString().toUpperCase(Locale.ROOT));
      assumeTrue(Files.exists(upperCase), "needs a case-insensitive filesystem");
    } finally {
      Files.delete(probe);
    }
  }
}
