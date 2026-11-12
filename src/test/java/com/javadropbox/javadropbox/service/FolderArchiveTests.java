package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.javadropbox.javadropbox.repository.ListedRow;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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

  @TempDir Path serving;

  private LocalFileStore store;
  private StoragePaths.Home home;
  private Path folder;
  // The account's rows: what the file list shows, and so what a zip has.
  private final List<ListedRow> rows = new ArrayList<>();

  // The store refuses a path with a symlink along it, which a temp directory can have (/var on
  // macOS), so it is given the real one, as the app's is.
  @BeforeEach
  void setUp() throws IOException {
    serving = serving.toRealPath();
    store = new LocalFileStore(serving);
    home = new StoragePaths(store, serving).home(1);
    folder = Files.createDirectories(store.path(home.key()).resolve("project"));
    rows.add(new ListedRow("project", true, 0L, Instant.now()));
  }

  @Test
  @DisplayName("hidden files and folders are left out, as in the file tree")
  void hiddenEntriesAreLeftOut() throws IOException {
    file("README.md", "visible");
    file(".env", "DB_PASSWORD=hunter2");
    file(".upload-123.tmp", "half an upload");
    file(".git/config", "[remote]");
    folder(".git");
    folder("sub");
    file("sub/kept.txt", "kept");
    file("sub/.hidden", "hidden");

    assertThat(unzip(archive()))
        .containsOnly(
            Map.entry("project/", ""),
            Map.entry("project/README.md", "visible"),
            Map.entry("project/sub/", ""),
            Map.entry("project/sub/kept.txt", "kept"));
  }

  @Test
  @DisplayName("empty folders, folders with nothing to archive, and folders with no row appear")
  void emptyFoldersAreKept() throws IOException {
    folder("empty");
    folder("only-hidden");
    file("only-hidden/.env", "secret");
    // No row for "implied", only for what is in it.
    file("implied/deep.txt", "deep");

    assertThat(unzip(archive()))
        .containsOnlyKeys(
            "project/",
            "project/empty/",
            "project/implied/",
            "project/implied/deep.txt",
            "project/only-hidden/");
  }

  @Test
  @DisplayName("what the file list shows is zipped, not what else is on disk")
  void onlyListedItemsAreZipped() throws IOException {
    file("listed.txt", "listed");
    Files.writeString(folder.resolve("unlisted.txt"), "no row");
    Files.createDirectories(folder.resolve("unlisted-folder"));
    // A row whose content isn't there is left out, and the zip stays whole.
    rows.add(new ListedRow("project/missing.txt", false, 3L, Instant.now()));
    // Nor anything outside the folder.
    rows.add(new ListedRow("project-other.txt", false, 3L, Instant.now()));
    Files.writeString(folder.resolveSibling("project-other.txt"), "beside it");

    assertThat(unzip(archive())).containsOnlyKeys("project/", "project/listed.txt");
  }

  @Test
  @DisplayName("files removed while the zip is written are left out and the zip stays whole")
  void vanishedEntriesAreSkipped() throws IOException {
    for (int i = 0; i < 5; i++) {
      file("file" + i + ".txt", "content " + i);
    }
    folder("sub");
    file("sub/file5.txt", "content 5");
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

    write(deletingOnFirstWrite);

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
    file("a.txt", "a");
    Path fifo = folder.resolve("pipe");
    makeFifo(fifo);
    rows.add(new ListedRow("project/pipe", false, 0L, Instant.now()));

    try {
      byte[] zip = assertTimeoutPreemptively(Duration.ofSeconds(5), this::archive);

      assertThat(unzip(zip)).containsOnlyKeys("project/", "project/a.txt");
    } finally {
      // If the archive did open the FIFO, a writer lets that thread finish instead of leaking.
      // Opened read-write, which unlike write-only does not wait for a reader.
      new RandomAccessFile(fifo.toFile(), "rw").close();
    }
  }

  // A file in the folder, on disk and with its row.
  private void file(String path, String content) throws IOException {
    Path file = folder.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
    rows.add(new ListedRow("project/" + path, false, (long) content.length(), Instant.now()));
  }

  private void folder(String path) throws IOException {
    Files.createDirectories(folder.resolve(path));
    rows.add(new ListedRow("project/" + path, true, 0L, Instant.now()));
  }

  private byte[] archive() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    write(out);
    return out.toByteArray();
  }

  private void write(OutputStream out) throws IOException {
    FolderArchive.write(
        new FileContents(store),
        home.resolveItem("project"),
        ListedItems.below("project", rows),
        "project",
        out);
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
