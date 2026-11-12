package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.ListedRow;
import com.javadropbox.javadropbox.service.FileStore.Entry;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What a {@link SearchIndex} may index, whatever it is built on: what the file list shows in each
 * account's folder ({@link ListedItems}), from the accounts' rows, keyed by the account's id
 * followed by the item's path in it (see {@link StoragePaths.Home#indexKey}). Not the accounts'
 * folders themselves and no hidden names. Search results are confined to one account by these keys,
 * so every backend lists items through here rather than deciding for itself. A file's text is read
 * through {@link FileContents}.
 */
@Component
class IndexableItems {

  private static final Logger log = LoggerFactory.getLogger(IndexableItems.class);

  /** Where the rows come from: the database, or something standing in for it in tests. */
  interface Rows {

    /** The accounts, in the order of their ids. */
    List<Long> accounts();

    /** An account's rows at {@code path} and below it, or all of them for the empty path. */
    List<ListedRow> of(long account, String path);

    static Rows of(FileMetadataRepository files) {
      return new Rows() {
        @Override
        public List<Long> accounts() {
          return files.findOwnerIds();
        }

        @Override
        public List<ListedRow> of(long account, String path) {
          return path.isEmpty()
              ? files.listAllOf(account)
              : files.listAtOrBelow(account, path, FileMetadataRepository.below(path));
        }
      };
    }
  }

  /**
   * An item as the file list shows it.
   *
   * @param size in bytes; 0 for a folder
   * @param modified when its content last changed, as its row says; null for a folder
   */
  record Item(String name, boolean isDirectory, long size, Instant modified) {

    static Item of(ListedItems.Item listed) {
      return new Item(listed.name(), listed.folder(), listed.size(), listed.modified());
    }
  }

  private final Rows rows;
  private final StoragePaths storagePaths;
  private final FileContents contents;
  private final TextExtractor extractor;

  @Autowired
  IndexableItems(
      FileMetadataRepository files,
      StoragePaths storagePaths,
      FileContents contents,
      TextExtractor extractor) {
    this(Rows.of(files), storagePaths, contents, extractor);
  }

  IndexableItems(
      Rows rows, StoragePaths storagePaths, FileContents contents, TextExtractor extractor) {
    this.rows = rows;
    this.storagePaths = storagePaths;
    this.contents = contents;
    this.extractor = extractor;
  }

  interface ItemVisitor {
    void visit(String key, Item item) throws IOException;
  }

  interface GoneVisitor {
    void gone(String key) throws IOException;
  }

  /**
   * Compares what is indexed, each item's key and {@link Stamp}, with what the rows list. Calls
   * {@code changed} for each item that is new or differs, and then {@code gone} for each indexed
   * key that is no longer listed. A reconcile {@code stop} cuts short has not seen everything that
   * is still there, so nothing is then reported gone: deleting it would empty the index of what it
   * did not reach. Takes {@code indexed} over.
   */
  void reconcile(
      Map<String, Stamp> indexed, BooleanSupplier stop, ItemVisitor changed, GoneVisitor gone)
      throws IOException {
    for (long account : rows.accounts()) {
      boolean finished =
          visit(
              account,
              "",
              stop,
              (key, item) -> {
                if (!Stamp.of(item).equals(indexed.remove(key))) {
                  changed.visit(key, item);
                }
              });
      if (!finished) {
        return;
      }
    }
    if (stop.getAsBoolean()) {
      return;
    }
    for (String key : indexed.keySet()) {
      gone.gone(key);
    }
  }

  /**
   * Visits the item at {@code key} and everything below it, or every account's items for the empty
   * key. The key is an account's id and a path in its folder, as {@link StoragePaths.Home#indexKey}
   * makes it. One with {@code .}, {@code ..} or an empty part in it visits nothing.
   */
  void walkUnder(String key, BooleanSupplier stop, ItemVisitor visitor) throws IOException {
    if (key.isEmpty()) {
      for (long account : rows.accounts()) {
        if (!visit(account, "", stop, visitor)) {
          return;
        }
      }
      return;
    }
    if (!isKey(key)) {
      return;
    }
    int slash = key.indexOf('/');
    String account = slash < 0 ? key : key.substring(0, slash);
    if (!isAccount(account)) {
      return;
    }
    visit(Long.parseLong(account), slash < 0 ? "" : key.substring(slash + 1), stop, visitor);
  }

  // The items at path in the account's folder and below it, as the file list shows them. Whether
  // that went all the way, rather than being stopped.
  private boolean visit(long account, String path, BooleanSupplier stop, ItemVisitor visitor)
      throws IOException {
    String top = ListedItems.parentOf(path);
    for (ListedItems.Item item : ListedItems.below(top, rows.of(account, path))) {
      if (stop.getAsBoolean()) {
        return false;
      }
      // Only what is at path or below it: rows are all there, but a stray one is not trusted.
      if (path.isEmpty() || item.path().equals(path) || item.path().startsWith(path + "/")) {
        visitor.visit(account + "/" + item.path(), Item.of(item));
      }
    }
    return !stop.getAsBoolean();
  }

  private static boolean isKey(String key) {
    for (String segment : key.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        return false;
      }
    }
    return true;
  }

  // Accounts are keyed by their ids.
  private static boolean isAccount(String id) {
    return !id.isEmpty() && id.length() < 19 && id.chars().allMatch(c -> c >= '0' && c <= '9');
  }

  /**
   * The text of a file listed at {@code key}, for searching, in Unicode's composed form (NFC). A
   * file whose text cannot be read is still found by its name. One whose text would not be read
   * anyway is not fetched from the store.
   */
  Optional<String> text(String key, Item file) {
    String name = file.name();
    if (!extractor.reads(name, file.size())) {
      return Optional.empty();
    }
    try {
      int slash = key.indexOf('/');
      Optional<Entry> content =
          contents
              .of(
                  storagePaths
                      .home(Long.parseLong(key.substring(0, slash)))
                      .resolveItem(key.substring(slash + 1)))
              .filter(entry -> !entry.isDirectory());
      if (content.isEmpty()) {
        throw new NoSuchFileException(key);
      }
      long size = content.get().size();
      if (!extractor.reads(name, size)) {
        // Grown since its row was written: not fetched, as it wouldn't be read.
        return Optional.empty();
      }
      // Accents as one character with their letter ("é" rather than "e" and a combining accent), as
      // text from some PDFs and names copied from macOS have them, so that folding them away works.
      return contents
          .readLocally(content.get(), path -> extractor.extract(path, name, size))
          .filter(text -> !text.isBlank())
          .map(text -> Normalizer.normalize(text, Normalizer.Form.NFC));
    } catch (NoSuchFileException e) {
      // Deleted since it was listed; that delete's own change will drop it.
      log.debug("{} went before its text could be read", key);
      return Optional.empty();
    } catch (IOException | RuntimeException e) {
      // BadRequestException among them, for a way there that passes through a symbolic link.
      log.warn("Could not read the text of {} for search: {}", key, e.toString());
      return Optional.empty();
    }
  }

  /**
   * What a reconcile compares to tell whether an item changed: for a file, its size and when its
   * row says its content last changed. A folder has nothing to compare: its name is all that is
   * indexed of it, and that is part of its key.
   */
  record Stamp(boolean directory, long size, long modified) {

    static final Stamp FOLDER = new Stamp(true, 0, 0);

    static Stamp of(Item item) {
      return item.isDirectory() ? FOLDER : new Stamp(false, item.size(), millis(item));
    }

    static long millis(Item file) {
      return file.modified() != null ? file.modified().toEpochMilli() : -1;
    }
  }
}
