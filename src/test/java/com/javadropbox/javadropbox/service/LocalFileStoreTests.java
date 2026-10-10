package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Local file store")
class LocalFileStoreTests extends FileStoreContractTests {

  @TempDir Path tmp;

  private LocalFileStore store;

  // The real path, as the app's serving directory is resolved: a temp directory can be under a
  // symlink (/var on macOS), which the store refuses.
  @BeforeEach
  void setUp() throws IOException {
    store = new LocalFileStore(tmp.toRealPath());
  }

  @Override
  protected FileStore store() {
    return store;
  }

  @Test
  @DisplayName("a new folder gets a welcome file, an existing one is left as it is")
  void newFolderGetsAWelcomeFile() throws IOException {
    Path created = new LocalFileStore(tmp.resolve("new")).root();

    assertThat(created.resolve(StoragePaths.WELCOME_FILE)).hasContent(StoragePaths.WELCOME_TEXT);
    assertThat(tmp.resolve(StoragePaths.WELCOME_FILE)).doesNotExist();
  }

  @Test
  @DisplayName("the upload scratch file gets the permissions of any new file, not owner-only")
  void scratchFileHasDefaultPermissions() throws IOException {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    Path ordinary = Files.createFile(store.path("ordinary.txt"));

    Path scratch = store.path(store.scratchBeside("upload.txt"));

    assertThat(Files.getPosixFilePermissions(scratch))
        .isEqualTo(Files.getPosixFilePermissions(ordinary));
    assertThat(scratch.getFileName().toString()).startsWith(".");
  }

  @Test
  @DisplayName("symlinks are not items: not there, not listed and not walked")
  void symlinksAreNotItems() throws IOException {
    write("docs/a.txt", "a");
    Path outside = Files.createDirectory(tmp.resolve("outside"));
    Files.writeString(outside.resolve("secret.txt"), "secret");
    Files.createSymbolicLink(store.path("docs/link"), outside);
    Files.createSymbolicLink(store.path("docs/file-link"), store.path("docs/a.txt"));

    assertThat(keys(store.list("docs"))).containsExactly("docs/a.txt");
    assertThat(keys(walk("docs"))).containsExactlyInAnyOrder("docs", "docs/a.txt");
  }
}
