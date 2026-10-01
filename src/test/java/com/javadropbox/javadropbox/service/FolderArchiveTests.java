package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Folder archives")
class FolderArchiveTests {

  @TempDir Path folder;

  // The app archives paths built on the real storage root, and FolderArchive refuses a path with a
  // symlink along it, which a temp directory can have (/var on macOS).
  @BeforeEach
  void useRealPath() throws IOException {
    folder = folder.toRealPath();
  }

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
            Map.entry("project/", ""),
            Map.entry("project/README.md", "visible"),
            Map.entry("project/sub/", ""),
            Map.entry("project/sub/kept.txt", "kept"));
  }

  @Test
  @DisplayName("empty folders, and folders with nothing to archive, appear in the zip")
  void emptyFoldersAreKept() throws IOException {
    Files.createDirectories(folder.resolve("empty"));
    Files.createDirectories(folder.resolve("only-hidden"));
    Files.writeString(folder.resolve("only-hidden/.env"), "secret");

    assertThat(unzip(archive()))
        .containsOnlyKeys("project/", "project/empty/", "project/only-hidden/");
  }

  @Test
  @DisplayName("files removed while the zip is written are left out and the zip stays whole")
  void vanishedEntriesAreSkipped() throws IOException {
    for (int i = 0; i < 5; i++) {
      Files.writeString(folder.resolve("file" + i + ".txt"), "content " + i);
    }
    Files.createDirectories(folder.resolve("sub"));
    Files.writeString(folder.resolve("sub/file5.txt"), "content 5");
    ByteArrayOutputStream zip = new ByteArrayOutputStream();
    // The listing has been read by the time the first byte is written; everything goes now.
    OutputStream deletingOnFirstWrite =
        new FilterOutputStream(zip) {
          private boolean deleted;

          @Override
          public void write(byte[] b, int off, int len) throws IOException {
            if (!deleted) {
              deleted = true;
              deleteContents(folder);
            }
            out.write(b, off, len);
          }
        };

    FolderArchive.write(folder, "project", deletingOnFirstWrite);

    // Whatever made it in is complete; reading the zip to its end proves it is not cut short.
    Map<String, String> files = unzip(zip.toByteArray());
    files.keySet().removeIf(name -> name.endsWith("/"));
    assertThat(files).hasSizeLessThan(6);
    files.forEach(
        (name, content) -> assertThat(content).isEqualTo("content " + name.replaceAll("\\D", "")));
  }

  @Test
  @DisplayName("a FIFO is left out rather than read, which would block forever")
  void fifoIsSkipped() throws Exception {
    Files.writeString(folder.resolve("a.txt"), "a");
    Path fifo = folder.resolve("pipe");
    makeFifo(fifo);

    try {
      byte[] zip = assertTimeoutPreemptively(Duration.ofSeconds(5), this::archive);

      assertThat(unzip(zip)).containsOnlyKeys("project/", "project/a.txt");
    } finally {
      // If the archive did open the FIFO, a writer lets that thread finish instead of leaking.
      // Opened read-write, which unlike write-only does not wait for a reader.
      new RandomAccessFile(fifo.toFile(), "rw").close();
    }
  }

  private byte[] archive() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FolderArchive.write(folder, "project", out);
    return out.toByteArray();
  }

  /** Skips the test where there is no mkfifo, e.g. on Windows. */
  private static void makeFifo(Path path) throws InterruptedException {
    int exit;
    try {
      exit = new ProcessBuilder("mkfifo", path.toString()).inheritIO().start().waitFor();
    } catch (IOException e) {
      exit = -1;
    }
    assumeTrue(exit == 0, "needs mkfifo");
  }

  private static void deleteContents(Path dir) throws IOException {
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        if (!path.equals(dir)) {
          Files.delete(path);
        }
      }
    }
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
