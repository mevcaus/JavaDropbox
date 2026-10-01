package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.javadropbox.javadropbox.FileSystemAssumptions;
import com.javadropbox.javadropbox.exception.BadRequestException;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
  @ValueSource(strings = {".env", ".config"})
  @DisplayName("a new item whose name starts with a dot, which the tree would hide, is refused")
  void dotNamedChildIsRefused(String name) {
    assertThatThrownBy(() -> paths.resolveChild(paths.resolve(""), name))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("dot");
  }

  @Test
  @DisplayName("nothing new can be created inside a hidden folder")
  void newItemInsideHiddenFolderIsRefused() throws IOException {
    Files.createDirectory(root.resolve(".git"));

    assertThatThrownBy(() -> paths.resolve(".cache/new"))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("dot");
    assertThatThrownBy(() -> paths.resolveChild(paths.resolve(".git"), "config"))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("dot");
  }

  @Test
  @DisplayName("keys use the filesystem's own spelling of names that already exist")
  void keysUseTheOnDiskSpelling() throws IOException {
    FileSystemAssumptions.assumeCaseInsensitive(root);
    Files.createDirectory(root.resolve("Docs"));
    Files.writeString(root.resolve("Docs/Report.txt"), "report");

    assertThat(paths.resolve("DOCS/report.TXT").key()).isEqualTo("Docs/Report.txt");
    assertThat(paths.resolveChild(paths.resolve("docs"), "new.txt").key())
        .isEqualTo("Docs/new.txt");
  }

  @Test
  @DisplayName("keys use the on-disk spelling, checked on an in-memory case-insensitive filesystem")
  void keysUseTheOnDiskSpellingOnAnyPlatform() throws IOException {
    try (FileSystem macLike = Jimfs.newFileSystem(Configuration.osX())) {
      Path macRoot = Files.createDirectories(macLike.getPath("/srv/files"));
      Files.createDirectories(macRoot.resolve("Docs"));
      Files.writeString(macRoot.resolve("Docs/Report.txt"), "report");
      Files.createDirectories(macRoot.resolve(".versions"));
      StoragePaths macPaths = new StoragePaths(macRoot);

      assertThat(macPaths.resolve("DOCS/report.TXT").key()).isEqualTo("Docs/Report.txt");
      assertThat(macPaths.resolve("docs/new/file.txt").key()).isEqualTo("Docs/new/file.txt");
      assertThatThrownBy(() -> macPaths.resolve(".VERSIONS"))
          .isInstanceOf(BadRequestException.class);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {".VERSIONS", ".Versions/1/v1", ".JAVADROPBOX/share-jwt.key", ".JavaDropBox"})
  @DisplayName("the reserved folders are refused under any letter case, on any filesystem")
  void reservedFoldersAreRefusedInAnyCase(String path) {
    assertThatThrownBy(() -> paths.resolve(path)).isInstanceOf(BadRequestException.class);
  }
}
