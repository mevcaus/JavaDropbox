package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Storage files")
class StorageFilesTests {

  @TempDir Path tmp;

  @Test
  @DisplayName("the upload scratch file gets the permissions of any new file, not owner-only")
  void scratchFileHasDefaultPermissions() throws IOException {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    Path folder = tmp.toRealPath();
    Path ordinary = Files.createFile(folder.resolve("ordinary.txt"));

    Path scratch = StorageFiles.tempFileBeside(folder.resolve("upload.txt"));

    assertThat(Files.getPosixFilePermissions(scratch))
        .isEqualTo(Files.getPosixFilePermissions(ordinary));
    assertThat(scratch.getFileName().toString()).startsWith(".");
  }
}
