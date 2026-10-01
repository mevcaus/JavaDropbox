package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Folder archives")
class FolderArchiveTests {

  @TempDir Path folder;

  @Test
  @DisplayName("hidden files and folders are left out, as in the file tree")
  void hiddenEntriesAreLeftOut() throws IOException {
    Files.writeString(folder.resolve("README.md"), "visible");
    Files.writeString(folder.resolve(".env"), "DB_PASSWORD=hunter2");
    Files.writeString(folder.resolve(".DS_Store"), "x");
    Files.writeString(folder.resolve(".upload-123.tmp"), "half an upload");
    Files.createDirectories(folder.resolve(".git"));
    Files.writeString(folder.resolve(".git/config"), "[remote]");
    Files.createDirectories(folder.resolve("sub"));
    Files.writeString(folder.resolve("sub/kept.txt"), "kept");
    Files.writeString(folder.resolve("sub/.hidden"), "hidden");

    assertThat(unzip(archive()))
        .containsOnly(
            Map.entry("project/README.md", "visible"), Map.entry("project/sub/kept.txt", "kept"));
  }

  private byte[] archive() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FolderArchive.write(folder, "project", out);
    return out.toByteArray();
  }

  /** Every entry's name and content; reading each entry to its end also checks the zip is whole. */
  private static Map<String, String> unzip(byte[] zip) throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
        entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return entries;
  }
}
