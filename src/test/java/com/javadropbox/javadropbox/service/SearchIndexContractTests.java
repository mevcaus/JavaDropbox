package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.service.StoragePaths.Home;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * What every {@link SearchIndex} has to do, whatever it is built on. A backend's own test class
 * extends this and says how to open and close one; it adds tests of whatever only it does.
 */
abstract class SearchIndexContractTests {

  static final Duration INDEXING = Duration.ofSeconds(30);

  @TempDir Path servingDir;

  LocalFileStore store;
  StoragePaths storagePaths;
  // The folder of the account the tests search, and where they put its files.
  Home home;
  Path files;
  final AtomicInteger extracted = new AtomicInteger();
  private final List<SearchIndex> opened = new ArrayList<>();

  // Counts the files whose text is read.
  final TextExtractor extractor =
      new TextExtractor("50MB", Path.of(System.getProperty("java.io.tmpdir"))) {
        @Override
        public Optional<String> extract(Path file, String name, long size) throws IOException {
          extracted.incrementAndGet();
          return super.extract(file, name, size);
        }
      };

  /** A new index over {@link #store}, reading text with {@link #extractor}. */
  abstract SearchIndex create() throws IOException;

  /** Closes an index {@link #create} made, as the app does at shutdown. */
  abstract void close(SearchIndex index) throws Exception;

  @BeforeEach
  void setUpStore() throws IOException {
    store = new LocalFileStore(servingDir);
    storagePaths = new StoragePaths(store, servingDir);
    home = storagePaths.home(1);
    files = store.path(home.key());
  }

  @AfterEach
  void closeIndexes() throws Exception {
    for (SearchIndex index : opened) {
      close(index);
    }
  }

  @Test
  @DisplayName("a search finds the account's own files only, not another's or loose ones")
  void searchFindsTheAccountsOwnFilesOnly() throws Exception {
    Files.writeString(files.resolve("mine.txt"), "shared word");
    Files.writeString(store.path(storagePaths.home(2).key()).resolve("theirs.txt"), "shared word");
    // Account 12's key starts with account 1's id.
    Files.writeString(
        store.path(storagePaths.home(12).key()).resolve("also-theirs.txt"), "shared word");
    Path homes = store.path(StoragePaths.HOMES_DIR);
    Files.writeString(homes.resolve("loose.txt"), "shared word");
    Files.createDirectories(homes.resolve("stray"));
    Files.writeString(homes.resolve("stray/stray.txt"), "shared word");
    SearchIndex index = open();
    reconcile(index);

    assertThat(paths(index, "shared")).containsExactly("mine.txt");
    assertThat(
            index.search(storagePaths.home(2), "", "shared", 10).hits().stream()
                .map(SearchIndex.Hit::path))
        .containsExactly("theirs.txt");
    // The account's folder is no item of its own.
    assertThat(paths(index, "1")).isEmpty();
  }

  @Test
  @DisplayName("a reconcile reads again only the files whose size or modification time changed")
  void reconcileReadsOnlyWhatChanged() throws Exception {
    Path a = Files.writeString(files.resolve("a.txt"), "alpha");
    Files.writeString(files.resolve("b.txt"), "beta");
    SearchIndex index = open();
    reconcile(index);
    assertThat(extracted).hasValue(2);

    reconcile(index);
    assertThat(extracted).hasValue(2);

    Files.writeString(a, "alpha, changed");
    Files.setLastModifiedTime(a, FileTime.from(Instant.now().plusSeconds(60)));
    reconcile(index);
    assertThat(extracted).hasValue(3);
    assertThat(paths(index, "changed")).containsExactly("a.txt");
  }

  @Test
  @DisplayName("wildcards typed into a search match only themselves")
  void wildcardsAreLiteral() throws Exception {
    Files.writeString(files.resolve("a*b.txt"), "");
    Files.writeString(files.resolve("c?d.txt"), "");
    Files.writeString(files.resolve("plain.txt"), "");
    SearchIndex index = open();
    reconcile(index);

    assertThat(paths(index, "*")).containsExactly("a*b.txt");
    assertThat(paths(index, "?")).containsExactly("c?d.txt");
  }

  // SearchService reports a hit it could not resolve, which may be gone or behind a symlink now.
  @Test
  @DisplayName("an item reported changed that is no longer there is dropped, without failing")
  void changedItemThatIsGoneIsDropped() throws Exception {
    Path gone = Files.writeString(files.resolve("gone.txt"), "vanishing");
    SearchIndex index = open();
    reconcile(index);
    assertThat(paths(index, "vanishing")).containsExactly("gone.txt");

    Files.delete(gone);
    index.changed(home, "gone.txt");
    index.changed(home, "never-was.txt");
    index.awaitIdle(INDEXING);

    assertThat(paths(index, "vanishing")).isEmpty();
  }

  // A change made inside a transaction is indexed once the transaction has finished, whichever way
  // it did: a rollback has put the store back, and the index then matches that.
  @Test
  @DisplayName("a change reported inside a transaction is indexed when it ends, even rolled back")
  void changeInsideATransactionWaitsForIt() throws Exception {
    SearchIndex index = open();
    reconcile(index);
    Files.writeString(files.resolve("late.txt"), "patience");

    TransactionSynchronizationManager.initSynchronization();
    List<TransactionSynchronization> registered;
    try {
      index.changed(home, "late.txt");
      registered = TransactionSynchronizationManager.getSynchronizations();
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    index.awaitIdle(INDEXING);
    assertThat(paths(index, "patience")).isEmpty();

    registered.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    index.awaitIdle(INDEXING);
    assertThat(paths(index, "patience")).containsExactly("late.txt");
  }

  SearchIndex open() throws IOException {
    SearchIndex index = create();
    opened.add(index);
    return index;
  }

  /** Closes an index before the test ends, so that another can be opened on what it left. */
  void closeEarly(SearchIndex index) throws Exception {
    opened.remove(index);
    close(index);
  }

  static void reconcile(SearchIndex index) throws InterruptedException {
    index.reconcile();
    index.awaitIdle(INDEXING);
  }

  List<String> paths(SearchIndex index, String text) throws IOException {
    return index.search(home, "", text, 10).hits().stream().map(SearchIndex.Hit::path).toList();
  }
}
