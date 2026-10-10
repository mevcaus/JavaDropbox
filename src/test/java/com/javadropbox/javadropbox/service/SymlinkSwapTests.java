package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.service.FileStore.Entry;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import com.javadropbox.javadropbox.service.StoragePaths.StoragePath;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

/**
 * A folder swapped for a symlink after its path was checked, as someone with write access to the
 * serving directory could race. Each operation must check again right before it touches the disk.
 */
@DisplayName("A symlink swapped in after the path check")
class SymlinkSwapTests {

  @TempDir Path tmp;

  private LocalFileStore store;
  // The folder of the account the paths are resolved for.
  private Path root;
  private Path outside;
  private Home paths;

  @BeforeEach
  void setUp() throws IOException {
    Path serving = Files.createDirectory(tmp.resolve("root"));
    store = new LocalFileStore(serving);
    paths = new StoragePaths(store, serving).home(1);
    root = store.path(paths.key());
    outside = Files.createDirectory(tmp.resolve("outside"));
    Files.writeString(outside.resolve("secret.txt"), "outside secret");
    Files.createDirectories(outside.resolve("sub"));
    Files.writeString(outside.resolve("sub/precious.txt"), "precious");
    Files.createDirectories(root.resolve("dir/sub"));
    Files.writeString(root.resolve("dir/secret.txt"), "inside");
  }

  @Test
  @DisplayName("is not followed when a folder is zipped")
  void zipDoesNotFollowSwappedLink() throws IOException {
    StoragePath checked = paths.resolveItem("dir");
    swapDirForLinkOutside();

    ByteArrayOutputStream zip = new ByteArrayOutputStream();
    assertThatThrownBy(() -> FolderArchive.write(store, checked.storeKey(), "dir", zip))
        .isInstanceOf(BadRequestException.class);
    assertThat(zip.toString()).doesNotContain("outside secret").doesNotContain("precious");
  }

  @Test
  @DisplayName("is not followed when something is deleted")
  void deleteDoesNotFollowSwappedLink() throws IOException {
    StoragePath checked = paths.resolveItem("dir/sub");
    swapDirForLinkOutside();

    assertThatThrownBy(() -> store.deleteRecursively(checked.storeKey()))
        .isInstanceOf(BadRequestException.class);
    assertThat(outside.resolve("sub/precious.txt")).exists();
  }

  @Test
  @DisplayName("is not followed when an upload is moved into place")
  void uploadDoesNotFollowSwappedLink() throws IOException {
    StoragePath checked = paths.resolveItem("dir/new.txt");
    // Kept outside the folder that is swapped, as a scratch file written before the swap would be
    // if it survived it.
    String held = paths.resolveItem("held.tmp").storeKey();
    store.write(held, new ByteArrayInputStream("uploaded".getBytes(StandardCharsets.UTF_8)), 8);
    swapDirForLinkOutside();

    assertThatThrownBy(() -> store.scratchBeside(checked.storeKey()))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> store.replace(held, checked.storeKey()))
        .isInstanceOf(BadRequestException.class);
    assertThat(outside.resolve("new.txt")).doesNotExist();
  }

  @Test
  @DisplayName("is not followed when a file is sent")
  void downloadDoesNotFollowSwappedLink() throws IOException {
    StoragePath checked = paths.resolveItem("dir/secret.txt");
    Entry file = store.stat(checked.storeKey()).orElseThrow();
    Resource content = store.resource(file);
    swapDirForLinkOutside();

    assertThatThrownBy(content::getInputStream).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(content::contentLength).isInstanceOf(BadRequestException.class);
  }

  private void swapDirForLinkOutside() throws IOException {
    try (Stream<Path> walk = Files.walk(root.resolve("dir"))) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
    Files.createSymbolicLink(root.resolve("dir"), outside);
  }
}
