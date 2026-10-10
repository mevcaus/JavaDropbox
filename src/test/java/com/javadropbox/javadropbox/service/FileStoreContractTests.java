package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.service.FileStore.Entry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

/**
 * What every {@link FileStore} does, whatever it is built on: the services above it rely on each
 * store behaving like a filesystem. Each store's tests run these against it.
 */
abstract class FileStoreContractTests {

  /** The store under test, empty but for what the test puts in it. */
  protected abstract FileStore store();

  // --- reading ---------------------------------------------------------------------------------

  @Test
  @DisplayName("a file written is there with its content, size and modification time")
  void writtenFileIsThere() throws IOException {
    Instant before = Instant.now().minusSeconds(5);

    long written = write("docs/a.txt", "alpha");

    assertThat(written).isEqualTo(5);
    Entry entry = store().stat("docs/a.txt").orElseThrow();
    assertThat(entry.isDirectory()).isFalse();
    assertThat(entry.size()).isEqualTo(5);
    assertThat(entry.name()).isEqualTo("a.txt");
    assertThat(entry.modified()).isAfter(before);
    assertThat(read("docs/a.txt")).isEqualTo("alpha");
  }

  @Test
  @DisplayName("content of a length not known up front is written all the same")
  void contentOfUnknownLengthIsWritten() throws IOException {
    store().createFolders("docs");

    long written = store().write("docs/a.txt", new ByteArrayInputStream("streamed".getBytes()), -1);

    assertThat(written).isEqualTo(8);
    assertThat(read("docs/a.txt")).isEqualTo("streamed");
  }

  @Test
  @DisplayName("writing a file again replaces its content")
  void writingAgainReplaces() throws IOException {
    write("a.txt", "a longer first version");
    write("a.txt", "short");

    assertThat(read("a.txt")).isEqualTo("short");
    assertThat(store().stat("a.txt").orElseThrow().size()).isEqualTo(5);
  }

  @Test
  @DisplayName("names in any script, with spaces and punctuation, are kept as they are")
  void unusualNamesRoundTrip() throws IOException {
    String key = "Ünïcødé 文件/Café #1 & 50% (draft)+.txt";

    write(key, "kept");

    assertThat(read(key)).isEqualTo("kept");
    assertThat(keys(store().list("Ünïcødé 文件"))).containsExactly(key);
  }

  @Test
  @DisplayName("nothing is at a key never written, and reading it says so")
  void missingKeyIsNotThere() throws IOException {
    assertThat(store().stat("nothing.txt")).isEmpty();
    assertThat(store().exists("nothing/below.txt")).isFalse();
    assertThatThrownBy(() -> store().open("nothing.txt")).isInstanceOf(NoSuchFileException.class);
    assertThatThrownBy(() -> store().list("nothing")).isInstanceOf(NoSuchFileException.class);
  }

  @Test
  @DisplayName("the root is always a folder")
  void rootIsAFolder() throws IOException {
    assertThat(store().stat("").orElseThrow().isDirectory()).isTrue();
  }

  @Test
  @DisplayName("a folder holding a file is a folder")
  void folderOfAFileIsAFolder() throws IOException {
    write("docs/sub/a.txt", "a");

    assertThat(store().stat("docs").orElseThrow().isDirectory()).isTrue();
    assertThat(store().stat("docs/sub").orElseThrow().isDirectory()).isTrue();
    assertThat(store().stat("docs/sub").orElseThrow().size()).isZero();
  }

  @Test
  @DisplayName("a file's resource is its content, and a range of it can be read on its own")
  void resourceReadsWholeAndInRanges() throws IOException {
    write("a.txt", "0123456789");
    Entry file = store().stat("a.txt").orElseThrow();
    Resource resource = store().resource(file);

    assertThat(resource.contentLength()).isEqualTo(10);
    try (InputStream in = resource.getInputStream()) {
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("0123456789");
    }
    // As Spring serves a range: skip to its start, read to its end, close.
    ByteArrayOutputStream range = new ByteArrayOutputStream();
    try (InputStream in = resource.getInputStream()) {
      StreamUtils.copyRange(in, range, 6, 8);
    }
    assertThat(range.toString(StandardCharsets.UTF_8)).isEqualTo("678");
  }

  @Test
  @DisplayName("a reader that needs the file on disk gets it, with the content")
  void readLocallyHandsOverAFile() throws IOException {
    write("a.txt", "on disk");

    String content = store().readLocally("a.txt", Files::readString);

    assertThat(content).isEqualTo("on disk");
  }

  // --- walking and listing ---------------------------------------------------------------------

  @Test
  @DisplayName("a walk visits the folder, then everything below, each folder before its contents")
  void walkVisitsFoldersFirst() throws IOException {
    write("top/a.txt", "a");
    write("top/sub/b.txt", "b");
    write("top/sub/deeper/c.txt", "c");
    store().createFolder("top/empty");
    write("top-sibling.txt", "not below top");

    List<Entry> visited = walk("top");

    assertThat(keys(visited))
        .containsExactlyInAnyOrder(
            "top",
            "top/a.txt",
            "top/empty",
            "top/sub",
            "top/sub/b.txt",
            "top/sub/deeper",
            "top/sub/deeper/c.txt");
    assertThat(visited.getFirst().key()).isEqualTo("top");
    for (int i = 1; i < visited.size(); i++) {
      String key = visited.get(i).key();
      String folder = key.substring(0, key.lastIndexOf('/'));
      assertThat(keys(visited.subList(0, i))).as("before %s", key).contains(folder);
    }
    assertThat(visited).filteredOn(Entry::isDirectory).extracting(Entry::size).containsOnly(0L);
    assertThat(visited)
        .filteredOn(entry -> entry.key().equals("top/sub/b.txt"))
        .extracting(Entry::size)
        .containsExactly(1L);
  }

  @Test
  @DisplayName(
      "a walk of the root visits everything; of a file, just the file; of nothing, nothing")
  void walkFromRootFileOrNothing() throws IOException {
    write("a.txt", "a");
    write("docs/b.txt", "b");

    assertThat(keys(walk(""))).containsExactlyInAnyOrder("", "a.txt", "docs", "docs/b.txt");
    assertThat(keys(walk("docs/b.txt"))).containsExactly("docs/b.txt");
    assertThat(walk("missing")).isEmpty();
  }

  @Test
  @DisplayName("a walk leaves out what a skipped folder holds, and stops when told to")
  void walkSkipsAndTerminates() throws IOException {
    write("skip/a.txt", "a");
    write("skip/sub/b.txt", "b");
    write("keep/c.txt", "c");

    List<String> visited = new ArrayList<>();
    store()
        .walk(
            "",
            entry -> {
              visited.add(entry.key());
              return entry.key().equals("skip")
                  ? FileVisitResult.SKIP_SUBTREE
                  : FileVisitResult.CONTINUE;
            });
    assertThat(visited).containsExactlyInAnyOrder("", "skip", "keep", "keep/c.txt");

    List<String> untilFirstFile = new ArrayList<>();
    store()
        .walk(
            "",
            entry -> {
              untilFirstFile.add(entry.key());
              return entry.isDirectory() ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
            });
    assertThat(untilFirstFile).filteredOn(key -> key.endsWith(".txt")).hasSize(1);
  }

  @Test
  @DisplayName("a listing has what is directly in the folder, files and folders")
  void listHasDirectContents() throws IOException {
    write("docs/a.txt", "a");
    write("docs/sub/b.txt", "b");
    store().createFolder("docs/empty");

    List<Entry> listed = store().list("docs");

    assertThat(keys(listed)).containsExactlyInAnyOrder("docs/a.txt", "docs/sub", "docs/empty");
    assertThat(listed)
        .filteredOn(Entry::isDirectory)
        .extracting(Entry::key)
        .containsExactlyInAnyOrder("docs/sub", "docs/empty");
    assertThat(keys(store().list(""))).containsExactly("docs");
    assertThat(store().list("docs/empty")).isEmpty();
  }

  // --- creating --------------------------------------------------------------------------------

  @Test
  @DisplayName("a folder created is there and empty, and cannot be created twice")
  void createdFolderIsEmpty() throws IOException {
    store().createFolder("docs");

    assertThat(store().stat("docs").orElseThrow().isDirectory()).isTrue();
    assertThat(store().list("docs")).isEmpty();
    assertThat(keys(walk(""))).containsExactlyInAnyOrder("", "docs");
    assertThatThrownBy(() -> store().createFolder("docs"))
        .isInstanceOf(FileAlreadyExistsException.class);
    write("a.txt", "a");
    assertThatThrownBy(() -> store().createFolder("a.txt"))
        .isInstanceOf(FileAlreadyExistsException.class);
  }

  @Test
  @DisplayName("folders are created with any missing above them, and again changes nothing")
  void createFoldersMakesTheWholePath() throws IOException {
    store().createFolders("a/b/c");
    store().createFolders("a/b/c");
    store().createFolders("a/b");

    assertThat(keys(walk("a"))).containsExactlyInAnyOrder("a", "a/b", "a/b/c");
    assertThat(store().stat("a/b/c").orElseThrow().isDirectory()).isTrue();
  }

  @Test
  @DisplayName("an empty file is created only where nothing is")
  void createFileOnlyWhereNothingIs() throws IOException {
    store().createFolder("docs");

    assertThat(store().createFile("docs/claimed.txt")).isTrue();
    assertThat(store().createFile("docs/claimed.txt")).isFalse();
    assertThat(store().createFile("docs")).isFalse();
    assertThat(store().stat("docs/claimed.txt").orElseThrow().size()).isZero();
  }

  @Test
  @DisplayName("a scratch file is a hidden name in the same folder, replaced onto its target")
  void scratchFileGoesBesideItsTarget() throws IOException {
    write("docs/a.txt", "old");

    String scratch = store().scratchBeside("docs/a.txt");
    store().write(scratch, new ByteArrayInputStream("new".getBytes()), 3);
    store().replace(scratch, "docs/a.txt");

    assertThat(scratch).startsWith("docs/.");
    assertThat(read("docs/a.txt")).isEqualTo("new");
    assertThat(store().exists(scratch)).isFalse();
  }

  // --- copying and moving ----------------------------------------------------------------------

  @Test
  @DisplayName("a copy has the same content, over whatever was there")
  void copyDuplicates() throws IOException {
    write("a.txt", "original");
    write("b.txt", "to be replaced");

    store().copy("a.txt", "b.txt");

    assertThat(read("a.txt")).isEqualTo("original");
    assertThat(read("b.txt")).isEqualTo("original");
  }

  @Test
  @DisplayName("a file moves to a free name, and refuses one that is taken")
  void moveFile() throws IOException {
    write("a.txt", "moving");
    write("taken.txt", "someone else's");
    store().createFolders("archive");

    store().move("a.txt", "archive/a.txt");

    assertThat(store().exists("a.txt")).isFalse();
    assertThat(read("archive/a.txt")).isEqualTo("moving");
    assertThatThrownBy(() -> store().move("archive/a.txt", "taken.txt"))
        .isInstanceOf(FileAlreadyExistsException.class);
    assertThat(read("taken.txt")).isEqualTo("someone else's");
  }

  @Test
  @DisplayName("a folder moves with everything in it, empty folders included")
  void moveFolder() throws IOException {
    write("docs/a.txt", "a");
    write("docs/sub/b.txt", "b");
    store().createFolder("docs/empty");
    store().createFolders("home");

    store().move("docs", "home/docs");

    assertThat(store().exists("docs")).isFalse();
    assertThat(keys(walk("home/docs")))
        .containsExactlyInAnyOrder(
            "home/docs",
            "home/docs/a.txt",
            "home/docs/sub",
            "home/docs/sub/b.txt",
            "home/docs/empty");
    assertThat(read("home/docs/sub/b.txt")).isEqualTo("b");
  }

  @Test
  @DisplayName("replacing moves a file onto another, which goes")
  void replaceMovesOnto() throws IOException {
    write("new.txt", "new");
    write("live.txt", "old");

    store().replace("new.txt", "live.txt");

    assertThat(store().exists("new.txt")).isFalse();
    assertThat(read("live.txt")).isEqualTo("new");
  }

  @Test
  @DisplayName("a file moved and touched counts as modified now")
  void touchedFileIsFresh() throws IOException {
    write("a.txt", "a");
    store().createFolders("versions");

    store().move("a.txt", "versions/v1");
    store().touch("versions/v1");

    assertThat(store().stat("versions/v1").orElseThrow().modified())
        .isAfter(Instant.now().minus(Duration.ofMinutes(1)));
  }

  // --- deleting --------------------------------------------------------------------------------

  @Test
  @DisplayName("a file is deleted, and deleting what is not there changes nothing")
  void deleteFile() throws IOException {
    write("docs/a.txt", "a");

    store().delete("docs/a.txt");
    store().delete("docs/a.txt");
    store().delete("never-there.txt");

    assertThat(store().exists("docs/a.txt")).isFalse();
  }

  @Test
  @DisplayName("an empty folder can be deleted, but a folder with something in it is not emptied")
  void deleteOnlyEmptiesNothing() throws IOException {
    store().createFolder("empty");
    write("full/a.txt", "kept");

    store().delete("empty");
    try {
      store().delete("full");
    } catch (IOException e) {
      // Refusing is as good as leaving it.
    }

    assertThat(store().exists("empty")).isFalse();
    assertThat(read("full/a.txt")).isEqualTo("kept");
  }

  @Test
  @DisplayName("a folder is deleted with everything in it, and the folder that held it stays")
  void deleteRecursively() throws IOException {
    write("docs/sub/a.txt", "a");
    write("docs/sub/deeper/b.txt", "b");
    store().createFolder("docs/sub/empty");

    store().deleteRecursively("docs/sub");
    store().deleteRecursively("docs/sub");

    assertThat(store().exists("docs/sub")).isFalse();
    assertThat(store().exists("docs/sub/deeper/b.txt")).isFalse();
    assertThat(store().stat("docs").orElseThrow().isDirectory()).isTrue();
    assertThat(store().list("docs")).isEmpty();
  }

  @Test
  @DisplayName("deleting a folder's only file leaves the folder, empty")
  void deletingTheOnlyFileLeavesTheFolder() throws IOException {
    write("docs/a.txt", "a");

    store().deleteRecursively("docs/a.txt");

    assertThat(store().stat("docs").orElseThrow().isDirectory()).isTrue();
    assertThat(store().list("docs")).isEmpty();
  }

  // --- helpers ---------------------------------------------------------------------------------

  /** Writes a file, creating its folders first as the app does. */
  protected long write(String key, String content) throws IOException {
    int slash = key.lastIndexOf('/');
    if (slash > 0) {
      store().createFolders(key.substring(0, slash));
    }
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    return store().write(key, new ByteArrayInputStream(bytes), bytes.length);
  }

  protected String read(String key) throws IOException {
    try (InputStream in = store().open(key)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  protected List<Entry> walk(String key) throws IOException {
    List<Entry> visited = new ArrayList<>();
    store()
        .walk(
            key,
            entry -> {
              visited.add(entry);
              return FileVisitResult.CONTINUE;
            });
    return visited;
  }

  protected static List<String> keys(List<Entry> entries) {
    return entries.stream().map(Entry::key).toList();
  }
}
