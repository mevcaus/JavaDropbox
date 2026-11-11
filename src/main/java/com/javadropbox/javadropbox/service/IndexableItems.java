package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.service.FileStore.Entry;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.NoSuchFileException;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What a {@link SearchIndex} may index, whatever it is built on: every file and folder inside an
 * account's folder, keyed by the account's id followed by the item's path in it (see {@link
 * StoragePaths.Home#indexKey}). Not the accounts' folders themselves, not anything beside them, no
 * hidden names (the app's own folders among them), and nothing behind a symbolic link. Search
 * results are confined to one account by these keys, so every backend walks the store through here
 * rather than deciding for itself.
 */
@Component
class IndexableItems {

  private static final Logger log = LoggerFactory.getLogger(IndexableItems.class);

  // The folder of the accounts' folders: keys start with the account's id.
  private static final String HOMES = StoragePaths.HOMES_DIR;

  private final FileStore store;
  private final TextExtractor extractor;

  IndexableItems(FileStore store, TextExtractor extractor) {
    this.store = store;
    this.extractor = extractor;
  }

  interface ItemVisitor {
    void visit(String key, Entry entry) throws IOException;
  }

  interface GoneVisitor {
    void gone(String key) throws IOException;
  }

  /**
   * Compares what is indexed, each item's key and {@link Stamp}, with what is stored. Calls {@code
   * changed} for each item that is new or differs, and then {@code gone} for each indexed key that
   * is no longer stored. A walk {@code stop} cuts short has not seen everything that is still
   * there, so nothing is then reported gone: deleting it would empty the index of what the walk did
   * not reach. Takes {@code indexed} over, and leaves in it what was reported gone.
   */
  void reconcile(
      Map<String, Stamp> indexed, BooleanSupplier stop, ItemVisitor changed, GoneVisitor gone)
      throws IOException {
    walk(
        HOMES,
        stop,
        (key, entry) -> {
          if (!Stamp.of(entry).equals(indexed.remove(key))) {
            changed.visit(key, entry);
          }
        });
    if (stop.getAsBoolean()) {
      return;
    }
    for (String key : indexed.keySet()) {
      gone.gone(key);
    }
  }

  /**
   * Visits the item at {@code key}, and everything below it; every item there is for the empty key.
   * The key is an account's id and a path in its folder, as {@link StoragePaths.Home#indexKey}
   * makes it: what is above it is not checked again. One with {@code ..} or an empty part in it, or
   * whose way passes through a symbolic link, visits nothing.
   */
  void walkUnder(String key, BooleanSupplier stop, ItemVisitor visitor) throws IOException {
    if (!isKey(key)) {
      return;
    }
    try {
      walk(key.isEmpty() ? HOMES : HOMES + "/" + key, stop, visitor);
    } catch (BadRequestException e) {
      // The way there passes through a symlink, which could lead out of the serving directory.
    }
  }

  private static boolean isKey(String key) {
    if (key.isEmpty()) {
      return true;
    }
    for (String segment : key.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        return false;
      }
    }
    return true;
  }

  // The items the file tree lists: no hidden names (the app's own folders among them). The store
  // leaves out symlinks, and special files such as FIFOs, which reading would block on.
  private void walk(String start, BooleanSupplier stop, ItemVisitor visitor) throws IOException {
    store.walk(
        start,
        entry -> {
          if (stop.getAsBoolean()) {
            return FileVisitResult.TERMINATE;
          }
          if (entry.key().equals(HOMES)) {
            return FileVisitResult.CONTINUE;
          }
          if (entry.name().startsWith(".")) {
            return FileVisitResult.SKIP_SUBTREE;
          }
          String key = entry.key().substring(HOMES.length() + 1);
          // An account's folder is not an item in it, and anything beside them is nobody's.
          if (key.indexOf('/') < 0) {
            return entry.isDirectory() && isHome(key)
                ? FileVisitResult.CONTINUE
                : FileVisitResult.SKIP_SUBTREE;
          }
          visitor.visit(key, entry);
          return FileVisitResult.CONTINUE;
        });
  }

  // Accounts' folders are named after their ids.
  private static boolean isHome(String name) {
    return !name.isEmpty() && name.chars().allMatch(c -> c >= '0' && c <= '9');
  }

  /**
   * The text of a file the walk visited, for searching. A file whose text cannot be read is still
   * found by its name. One whose text would not be read anyway is not fetched from the store.
   */
  Optional<String> text(String key, Entry file) {
    String name = file.name();
    long size = file.size();
    if (!extractor.reads(name, size)) {
      return Optional.empty();
    }
    try {
      return store
          .readLocally(file.key(), path -> extractor.extract(path, name, size))
          .filter(text -> !text.isBlank());
    } catch (NoSuchFileException e) {
      // Deleted since the walk saw it; that delete's own change will drop it.
      log.debug("{} went before its text could be read", key);
      return Optional.empty();
    } catch (IOException | RuntimeException e) {
      log.warn("Could not read the text of {} for search: {}", key, e.toString());
      return Optional.empty();
    }
  }

  /**
   * What a reconcile compares to tell whether an item changed. A folder has nothing to compare: its
   * name is all that is indexed of it, and that is part of its key.
   */
  record Stamp(boolean directory, long size, long modified) {

    static final Stamp FOLDER = new Stamp(true, 0, 0);

    static Stamp of(Entry entry) {
      return entry.isDirectory() ? FOLDER : new Stamp(false, entry.size(), millis(entry));
    }

    static long millis(Entry file) {
      return file.modified() != null ? file.modified().toEpochMilli() : -1;
    }
  }
}
