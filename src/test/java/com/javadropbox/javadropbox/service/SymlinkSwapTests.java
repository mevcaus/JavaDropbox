package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import com.javadropbox.javadropbox.service.StoragePaths.StoragePath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A folder swapped for a symlink after its path was checked, as someone with write access to the
 * serving directory could race. Each operation must check again right before it touches the disk.
 */
@DisplayName("A symlink swapped in after the path check")
class SymlinkSwapTests {

  @TempDir Path tmp;

  // The folder of the account the paths are resolved for.
  private Path root;
  private Path outside;
  private Home paths;

  @BeforeEach
  void setUp() throws IOException {
    paths = new StoragePaths(Files.createDirectory(tmp.resolve("root")).toString()).home(1);
    root = paths.root();
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
    assertThatThrownBy(() -> FolderArchive.write(checked.path(), "dir", zip))
        .isInstanceOf(BadRequestException.class);
    assertThat(zip.toString()).doesNotContain("outside secret").doesNotContain("precious");
  }

  @Test
  @DisplayName("is not followed when something is deleted")
  void deleteDoesNotFollowSwappedLink() throws IOException {
    StoragePath checked = paths.resolveItem("dir/sub");
    swapDirForLinkOutside();

    assertThatThrownBy(() -> StorageFiles.deleteRecursively(checked.path()))
        .isInstanceOf(BadRequestException.class);
    assertThat(outside.resolve("sub/precious.txt")).exists();
  }

  @Test
  @DisplayName("is not followed when an upload is moved into place")
  void uploadDoesNotFollowSwappedLink() throws IOException {
    StoragePath checked = paths.resolveItem("dir/new.txt");
    Path scratch = StorageFiles.tempFileBeside(checked.path());
    Files.writeString(scratch, "uploaded");
    Path heldScratch = Files.move(scratch, tmp.resolve("scratch"));
    swapDirForLinkOutside();

    assertThatThrownBy(() -> StorageFiles.tempFileBeside(checked.path()))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> StorageFiles.moveIntoPlace(heldScratch, checked.path()))
        .isInstanceOf(BadRequestException.class);
    assertThat(outside.resolve("new.txt")).doesNotExist();
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
