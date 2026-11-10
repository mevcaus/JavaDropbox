package com.javadropbox.javadropbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Abandoned uploads in the spool folder")
class UploadSpoolConfigTests {

  @TempDir Path spool;

  @Test
  @DisplayName("parts older than the cutoff are deleted, newer ones and anything else are kept")
  void deletesOnlyOldParts() throws IOException {
    Instant now = Instant.now();
    Path old = part("upload_a_00000001.tmp", now.minus(Duration.ofDays(2)));
    Path fresh = part("upload_b_00000002.tmp", now.minus(Duration.ofMinutes(5)));
    Path other = part("notes.txt", now.minus(Duration.ofDays(2)));
    Files.createDirectory(spool.resolve("upload_c_00000003.tmp"));

    UploadSpoolConfig.deleteAbandoned(spool, now.minus(UploadSpoolConfig.ABANDONED_AFTER));

    assertThat(old).doesNotExist();
    assertThat(fresh).exists();
    assertThat(other).exists();
    assertThat(spool.resolve("upload_c_00000003.tmp")).isDirectory();
  }

  private Path part(String name, Instant written) throws IOException {
    Path file = Files.writeString(spool.resolve(name), "part");
    Files.setLastModifiedTime(file, FileTime.from(written));
    return file;
  }
}
