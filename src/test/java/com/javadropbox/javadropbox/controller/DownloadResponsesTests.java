package com.javadropbox.javadropbox.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.dto.Download.FileDownload;
import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.service.StoragePaths;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import com.javadropbox.javadropbox.service.StoragePaths.StoragePath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("Download responses")
class DownloadResponsesTests {

  @TempDir Path tmp;

  @Test
  @DisplayName("a folder swapped for a symlink after the path check is not followed")
  void swappedSymlinkIsNotFollowed() throws IOException {
    Home home = new StoragePaths(Files.createDirectory(tmp.resolve("root")).toString()).home(1);
    Path root = home.root();
    Path outside = Files.createDirectory(tmp.resolve("outside"));
    Files.writeString(outside.resolve("secret.txt"), "outside secret");
    Files.createDirectory(root.resolve("dir"));
    Files.writeString(root.resolve("dir/secret.txt"), "inside");
    StoragePath checked = home.resolveItem("dir/secret.txt");

    Files.delete(root.resolve("dir/secret.txt"));
    Files.delete(root.resolve("dir"));
    Files.createSymbolicLink(root.resolve("dir"), outside);

    FileDownload download = new FileDownload(checked.path(), "secret.txt", "text/plain");
    assertThatThrownBy(() -> DownloadResponses.send(download, new MockHttpServletResponse()))
        .isInstanceOf(BadRequestException.class);
  }
}
