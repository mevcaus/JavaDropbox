package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.exception.BadRequestException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Storage paths")
class StoragePathsTests {

  @TempDir Path tmp;

  private Path root;
  private StoragePaths paths;

  @BeforeEach
  void setUp() throws IOException {
    root = Files.createDirectory(tmp.resolve("root"));
    Files.createDirectories(root.resolve(".versions/1"));
    Files.createDirectories(root.resolve(".javadropbox"));
    paths = new StoragePaths(root.toString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "self",
        "self/.versions",
        "v",
        "v/1",
        "a/up",
        "a/up/.javadropbox/share-jwt.key",
        "link",
        "link/new.txt"
      })
  @DisplayName("a path through a symlink inside the serving directory is refused")
  void pathThroughInRootSymlinkIsRefused(String path) throws IOException {
    Files.createDirectories(root.resolve("a"));
    Files.createDirectories(root.resolve("sub"));
    Files.createSymbolicLink(root.resolve("self"), Path.of("."));
    Files.createSymbolicLink(root.resolve("v"), Path.of(".versions"));
    Files.createSymbolicLink(root.resolve("a/up"), Path.of(".."));
    Files.createSymbolicLink(root.resolve("link"), Path.of("sub"));

    assertThatThrownBy(() -> paths.resolve(path)).isInstanceOf(BadRequestException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {".VERSIONS", ".Versions/1/v1", ".JAVADROPBOX/share-jwt.key", ".JavaDropBox"})
  @DisplayName("the reserved folders are refused under any letter case, on any filesystem")
  void reservedFoldersAreRefusedInAnyCase(String path) {
    assertThatThrownBy(() -> paths.resolve(path)).isInstanceOf(BadRequestException.class);
  }
}
