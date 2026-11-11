package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.service.FileStore.Entry;
import com.javadropbox.javadropbox.service.IndexableItems.Stamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What any search index may index. Every backend walks the store through {@link IndexableItems},
 * and accounts are kept apart by what it lets through, so it is tested here on its own as well as
 * through each backend.
 */
@DisplayName("What a search index may index")
class IndexableItemsTests {

  @TempDir Path servingDir;
  @TempDir Path outside;

  private LocalFileStore store;
  private StoragePaths storagePaths;
  private Path mine;
  private boolean failToRead;

  private IndexableItems items;

  @BeforeEach
  void setUp() throws IOException {
    store = new LocalFileStore(servingDir);
    storagePaths = new StoragePaths(store, servingDir);
    mine = store.path(storagePaths.home(1).key());
    items =
        new IndexableItems(
            store,
            new TextExtractor("50MB", Path.of(System.getProperty("java.io.tmpdir"))) {
              @Override
              public Optional<String> extract(Path file, String name, long size)
                  throws IOException {
                if (failToRead) {
                  throw new IOException("unreadable");
                }
                return super.extract(file, name, size);
              }
            });
  }

  @Test
  @DisplayName("only what is inside accounts' folders, without hidden names, at any depth")
  void onlyTheAccountsVisibleItems() throws Exception {
    Files.createDirectories(mine.resolve("docs/.hidden"));
    Files.writeString(mine.resolve("docs/a.txt"), "a");
    Files.writeString(mine.resolve("docs/.secret.txt"), "s");
    Files.writeString(mine.resolve("docs/.hidden/b.txt"), "b");
    Files.writeString(store.path(storagePaths.home(12).key()).resolve("c.txt"), "c");
    Path homes = store.path(StoragePaths.HOMES_DIR);
    Files.writeString(homes.resolve("loose.txt"), "loose");
    Files.createDirectories(homes.resolve("stray"));
    Files.writeString(homes.resolve("stray/d.txt"), "d");
    Files.createDirectories(storagePaths.internalDir());
    Files.writeString(storagePaths.internalDir().resolve("e.txt"), "e");

    assertThat(reconcile(new HashMap<>()).changed)
        .containsExactlyInAnyOrder("1/docs", "1/docs/a.txt", "12/c.txt");
  }

  @Test
  @DisplayName("a reconcile reports what is new or changed, then what is gone")
  void reconcileReportsChangesAndWhatIsGone() throws Exception {
    Path kept = Files.writeString(mine.resolve("kept.txt"), "kept");
    Path changed = Files.writeString(mine.resolve("changed.txt"), "changed");
    Map<String, Stamp> indexed = new HashMap<>();
    indexed.put("1/kept.txt", Stamp.of(entry(kept)));
    indexed.put("1/changed.txt", new Stamp(false, 1, 1));
    indexed.put("1/gone.txt", new Stamp(false, 1, 1));
    Files.writeString(mine.resolve("new.txt"), "new");
    Files.setLastModifiedTime(changed, FileTime.from(Instant.now().plusSeconds(60)));

    Reported reported = reconcile(indexed);

    assertThat(reported.changed).containsExactlyInAnyOrder("1/changed.txt", "1/new.txt");
    assertThat(reported.gone).containsExactly("1/gone.txt");
  }

  // A shutdown during a reconcile must not empty the index of what the walk didn't reach.
  @Test
  @DisplayName("a reconcile cut short reports nothing gone")
  void reconcileCutShortReportsNothingGone() throws Exception {
    Files.writeString(mine.resolve("a.txt"), "a");
    Files.writeString(mine.resolve("b.txt"), "b");
    Map<String, Stamp> indexed = new HashMap<>();
    indexed.put("1/gone.txt", new Stamp(false, 1, 1));
    List<String> gone = new ArrayList<>();
    List<String> changed = new ArrayList<>();

    items.reconcile(indexed, () -> !changed.isEmpty(), (key, e) -> changed.add(key), gone::add);

    assertThat(changed).hasSize(1);
    assertThat(gone).isEmpty();
  }

  @Test
  @DisplayName("an item's walk covers it and what is below it")
  void walkUnderCoversTheItem() throws Exception {
    Files.createDirectories(mine.resolve("docs/deeper"));
    Files.writeString(mine.resolve("docs/deeper/a.txt"), "a");
    Files.writeString(mine.resolve("other.txt"), "o");

    assertThat(walkUnder("1/docs"))
        .containsExactlyInAnyOrder("1/docs", "1/docs/deeper", "1/docs/deeper/a.txt");
    assertThat(walkUnder("1/other.txt")).containsExactly("1/other.txt");
  }

  @Test
  @DisplayName("a key with .. or an empty part in it visits nothing")
  void keysThatLeaveTheFolderVisitNothing() throws Exception {
    Files.writeString(store.path(storagePaths.home(2).key()).resolve("theirs.txt"), "t");

    assertThat(walkUnder("1/../2/theirs.txt")).isEmpty();
    assertThat(walkUnder("1/..")).isEmpty();
    assertThat(walkUnder("1//2")).isEmpty();
    assertThat(walkUnder("1/./x")).isEmpty();
  }

  @Test
  @DisplayName("a key whose way passes through a symbolic link visits nothing")
  void keysThroughASymlinkVisitNothing() throws Exception {
    Files.writeString(outside.resolve("secret.txt"), "s");
    Files.createSymbolicLink(mine.resolve("link"), outside);

    assertThat(walkUnder("1/link/secret.txt")).isEmpty();
    assertThat(walkUnder("1/link")).isEmpty();
    assertThat(reconcile(new HashMap<>()).changed).isEmpty();
  }

  @Test
  @DisplayName("a file's text is read, and is nothing when it can't be or the file is gone")
  void textOrNothing() throws Exception {
    Path file = Files.writeString(mine.resolve("a.txt"), "some words");
    Entry entry = entry(file);
    assertThat(items.text("1/a.txt", entry)).contains("some words");

    failToRead = true;
    assertThat(items.text("1/a.txt", entry)).isEmpty();

    failToRead = false;
    Files.delete(file);
    assertThat(items.text("1/a.txt", entry)).isEmpty();
  }

  @Test
  @DisplayName("a file with no modification time is stamped as -1, a folder with nothing")
  void stamps() {
    assertThat(Stamp.of(new Entry("k", false, 5, null, null))).isEqualTo(new Stamp(false, 5, -1));
    assertThat(Stamp.of(new Entry("k", true, 0, null, Instant.now()))).isEqualTo(Stamp.FOLDER);
  }

  private record Reported(List<String> changed, List<String> gone) {}

  private Reported reconcile(Map<String, Stamp> indexed) throws IOException {
    Reported reported = new Reported(new ArrayList<>(), new ArrayList<>());
    items.reconcile(
        indexed, () -> false, (key, e) -> reported.changed.add(key), reported.gone::add);
    return reported;
  }

  private List<String> walkUnder(String key) throws IOException {
    List<String> keys = new ArrayList<>();
    items.walkUnder(key, () -> false, (k, e) -> keys.add(k));
    return keys;
  }

  // The entry the store's walk would give for a file.
  private Entry entry(Path file) throws IOException {
    String key = servingDir.relativize(file).toString().replace('\\', '/');
    return new Entry(
        key, false, Files.size(file), null, Files.getLastModifiedTime(file).toInstant());
  }
}
