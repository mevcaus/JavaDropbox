package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.repository.ListedRow;
import com.javadropbox.javadropbox.service.IndexableItems.Item;
import com.javadropbox.javadropbox.service.IndexableItems.Stamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What any search index may index. Every backend lists items through {@link IndexableItems}, and
 * accounts are kept apart by what it lets through, so it is tested here on its own as well as
 * through each backend.
 */
@DisplayName("What a search index may index")
class IndexableItemsTests {

  private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

  @TempDir Path servingDir;
  @TempDir Path outside;

  private LocalFileStore store;
  private StoragePaths storagePaths;
  private Path mine;
  private boolean failToRead;
  private int extracted;
  // Each account's rows, as the database would have them.
  private final Map<Long, List<ListedRow>> rows = new TreeMap<>();

  private IndexableItems items;

  @BeforeEach
  void setUp() throws IOException {
    store = new LocalFileStore(servingDir);
    storagePaths = new StoragePaths(store, servingDir);
    mine = store.path(storagePaths.home(1).key());
    IndexableItems.Rows source =
        new IndexableItems.Rows() {
          @Override
          public List<Long> accounts() {
            return List.copyOf(rows.keySet());
          }

          @Override
          public List<ListedRow> of(long account, String path) {
            return rows.getOrDefault(account, List.of()).stream()
                .filter(
                    row ->
                        path.isEmpty()
                            || row.path().equals(path)
                            || row.path().startsWith(path + "/"))
                .toList();
          }
        };
    items =
        new IndexableItems(
            source,
            storagePaths,
            new FileContents(store),
            new TextExtractor("50MB", Path.of(System.getProperty("java.io.tmpdir"))) {
              @Override
              public Optional<String> extract(Path file, String name, long size)
                  throws IOException {
                extracted++;
                if (failToRead) {
                  throw new IOException("unreadable");
                }
                return super.extract(file, name, size);
              }
            });
  }

  @Test
  @DisplayName("what the file list shows in each account, without hidden names, at any depth")
  void onlyTheAccountsListedItems() throws Exception {
    folder(1, "docs");
    folder(1, "docs/.hidden");
    file(1, "docs/a.txt", 1, T);
    file(1, "docs/.secret.txt", 1, T);
    file(1, "docs/.hidden/b.txt", 1, T);
    file(1, ".versions/x", 1, T);
    file(12, "c.txt", 1, T);

    assertThat(reconcile(new HashMap<>()).changed)
        .containsExactlyInAnyOrder("1/docs", "1/docs/a.txt", "12/c.txt");
  }

  @Test
  @DisplayName("folders are what the file list makes them: rows are below them, or they say so")
  void foldersAsTheFileListHasThem() throws Exception {
    // No row for a or a/b; a row for f that says it's a file, with a row below it.
    file(1, "a/b/c.txt", 1, T);
    file(1, "f", 1, T);
    file(1, "f/g.txt", 1, T);
    Map<String, Item> visited = new HashMap<>();

    items.walkUnder("1", () -> false, visited::put);

    assertThat(visited)
        .containsOnlyKeys("1/a", "1/a/b", "1/a/b/c.txt", "1/f", "1/f/g.txt")
        .hasEntrySatisfying("1/a", item -> assertThat(item.isDirectory()).isTrue())
        .hasEntrySatisfying("1/a/b", item -> assertThat(item.isDirectory()).isTrue())
        .hasEntrySatisfying("1/f", item -> assertThat(Stamp.of(item)).isEqualTo(Stamp.FOLDER));
  }

  @Test
  @DisplayName("a reconcile reports what is new, changed in size or time, then what is gone")
  void reconcileReportsChangesAndWhatIsGone() throws Exception {
    file(1, "kept.txt", 4, T);
    file(1, "touched.txt", 4, T.plusSeconds(60));
    file(1, "grown.txt", 13, T);
    folder(1, "folder");
    file(1, "new.txt", 3, T);
    Map<String, Stamp> indexed = new HashMap<>();
    indexed.put("1/kept.txt", new Stamp(false, 4, T.toEpochMilli()));
    indexed.put("1/touched.txt", new Stamp(false, 4, T.toEpochMilli()));
    indexed.put("1/grown.txt", new Stamp(false, 5, T.toEpochMilli()));
    indexed.put("1/folder", Stamp.FOLDER);
    indexed.put("1/gone.txt", new Stamp(false, 1, 1));

    Reported reported = reconcile(indexed);

    assertThat(reported.changed)
        .containsExactlyInAnyOrder("1/touched.txt", "1/grown.txt", "1/new.txt");
    assertThat(reported.gone).containsExactly("1/gone.txt");
  }

  // A shutdown during a reconcile must not empty the index of what it didn't reach.
  @Test
  @DisplayName("a reconcile cut short reports nothing gone")
  void reconcileCutShortReportsNothingGone() throws Exception {
    file(1, "a.txt", 1, T);
    file(2, "b.txt", 1, T);
    Map<String, Stamp> indexed = new HashMap<>();
    indexed.put("1/gone.txt", new Stamp(false, 1, 1));
    List<String> gone = new ArrayList<>();
    List<String> changed = new ArrayList<>();

    items.reconcile(indexed, () -> !changed.isEmpty(), (key, e) -> changed.add(key), gone::add);

    assertThat(changed).hasSize(1);
    assertThat(gone).isEmpty();
  }

  // The reconcile itself knows it was stopped: a stop that says so only once is enough.
  @Test
  @DisplayName("a reconcile stopped once reports nothing gone, even if asked again it wouldn't be")
  void reconcileStoppedOnceReportsNothingGone() throws Exception {
    file(1, "a.txt", 1, T);
    Map<String, Stamp> indexed = new HashMap<>();
    indexed.put("1/gone.txt", new Stamp(false, 1, 1));
    boolean[] asked = {false};
    List<String> gone = new ArrayList<>();

    items.reconcile(
        indexed,
        () -> {
          boolean first = !asked[0];
          asked[0] = true;
          return first;
        },
        (key, e) -> {},
        gone::add);

    assertThat(gone).isEmpty();
  }

  @Test
  @DisplayName("an item's walk covers it and what is below it, an account's all of its items")
  void walkUnderCoversTheItem() throws Exception {
    folder(1, "docs");
    file(1, "docs/deeper/a.txt", 1, T);
    file(1, "docs-other.txt", 1, T);
    file(1, "other.txt", 1, T);
    file(2, "theirs.txt", 1, T);

    assertThat(walkUnder("1/docs"))
        .containsExactlyInAnyOrder("1/docs", "1/docs/deeper", "1/docs/deeper/a.txt");
    assertThat(walkUnder("1/other.txt")).containsExactly("1/other.txt");
    assertThat(walkUnder("1")).hasSize(5).doesNotContain("2/theirs.txt");
    assertThat(walkUnder("")).hasSize(6);
    assertThat(walkUnder("1/nothing")).isEmpty();
  }

  @Test
  @DisplayName("a key with .. or an empty part in it, or no account's id first, visits nothing")
  void keysThatLeaveTheFolderVisitNothing() throws Exception {
    file(2, "theirs.txt", 1, T);
    folder(1, "2");
    file(1, "x", 1, T);

    assertThat(walkUnder("1/../2/theirs.txt")).isEmpty();
    assertThat(walkUnder("1/..")).isEmpty();
    assertThat(walkUnder("1//2")).isEmpty();
    assertThat(walkUnder("1/./x")).isEmpty();
    assertThat(walkUnder("x/2")).isEmpty();
    assertThat(walkUnder("99999999999999999999/x")).isEmpty();
  }

  @Test
  @DisplayName(
      "a file's text is read through its content, and is nothing when it can't be or is gone")
  void textOrNothing() throws Exception {
    Path file = Files.writeString(mine.resolve("a.txt"), "some words");
    Item item = item("a.txt", file);
    assertThat(items.text("1/a.txt", item)).contains("some words");

    failToRead = true;
    assertThat(items.text("1/a.txt", item)).isEmpty();

    failToRead = false;
    Files.delete(file);
    assertThat(items.text("1/a.txt", item)).isEmpty();
  }

  @Test
  @DisplayName("a file's text whose way passes through a symbolic link is not read")
  void textThroughASymlinkIsNotRead() throws Exception {
    Files.writeString(outside.resolve("secret.txt"), "secret words");
    Files.createSymbolicLink(mine.resolve("link"), outside);

    assertThat(items.text("1/link/secret.txt", new Item("secret.txt", false, 12, T))).isEmpty();
    assertThat(extracted).isZero();
  }

  @Test
  @DisplayName("a file whose text wouldn't be read is not fetched, and blank text is nothing")
  void textNotFetchedWhenNotRead() throws Exception {
    Path binary = Files.write(mine.resolve("data.bin"), new byte[] {1, 2, 3});
    Path blank = Files.writeString(mine.resolve("blank.txt"), "  \n ");
    extracted = 0;

    assertThat(items.text("1/data.bin", item("data.bin", binary))).isEmpty();
    assertThat(extracted).isZero();
    assertThat(items.text("1/blank.txt", item("blank.txt", blank))).isEmpty();
  }

  @Test
  @DisplayName("text is in Unicode's composed form, an accent one character with its letter")
  void textIsComposed() throws Exception {
    Path file = Files.writeString(mine.resolve("cafe.txt"), "Café");
    assertThat(items.text("1/cafe.txt", item("cafe.txt", file))).contains("Café");
  }

  @Test
  @DisplayName("a file is stamped with its row's size and time, -1 for none, a folder with nothing")
  void stamps() {
    assertThat(Stamp.of(new Item("k", false, 5, T)))
        .isEqualTo(new Stamp(false, 5, T.toEpochMilli()));
    assertThat(Stamp.of(new Item("k", false, 5, null))).isEqualTo(new Stamp(false, 5, -1));
    assertThat(Stamp.of(new Item("k", true, 0, T))).isEqualTo(Stamp.FOLDER);
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

  private void file(long account, String path, long size, Instant updated) {
    rows.computeIfAbsent(account, a -> new ArrayList<>())
        .add(new ListedRow(path, false, size, updated));
  }

  private void folder(long account, String path) {
    rows.computeIfAbsent(account, a -> new ArrayList<>()).add(new ListedRow(path, true, 0L, T));
  }

  private static Item item(String name, Path file) throws IOException {
    return new Item(name, false, Files.size(file), T);
  }
}
