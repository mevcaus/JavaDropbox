package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.ImportResult;
import com.javadropbox.javadropbox.exception.ConflictException;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.FileStore.Entry;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gives a row to everything in an account's folder that doesn't have one yet, such as files copied
 * in by hand. An admin starts it; nothing runs it on a timer.
 *
 * <p>It only ever adds rows. A row whose file or folder is missing from the store is counted and
 * left alone, and when none of an account's rows is in the store, the store is more likely the
 * wrong serving directory or bucket than one whose files were all removed, so nothing is imported.
 */
@Service
public class ImportService {

  /** Rows added per transaction, so a large import never holds many uncommitted rows at once. */
  static final int BATCH_SIZE = 500;

  /** The longest path a row can keep. */
  private static final int MAX_PATH_LENGTH = 4096;

  /** How many of the rows with nothing in the store the log names, by id. */
  private static final int MISSING_IDS_LOGGED = 100;

  private static final Logger log = LoggerFactory.getLogger(ImportService.class);

  private final StoragePaths storagePaths;
  private final FileStore store;
  private final FileMetadataRepository files;
  private final UserRepository users;
  private final SearchIndex searchIndex;
  private final StorageQuota quota;
  private final FileRows rows;
  private final FileVersionService versions;
  private final TransactionTemplate transactions;

  public ImportService(
      StoragePaths storagePaths,
      FileStore store,
      FileMetadataRepository files,
      UserRepository users,
      SearchIndex searchIndex,
      StorageQuota quota,
      FileRows rows,
      FileVersionService versions,
      PlatformTransactionManager transactionManager) {
    this.storagePaths = storagePaths;
    this.store = store;
    this.files = files;
    this.users = users;
    this.searchIndex = searchIndex;
    this.quota = quota;
    this.rows = rows;
    this.versions = versions;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Imports what is in the folder of the account with id {@code userId}.
   *
   * @param startedBy who asked for it, for the log
   * @throws ConflictException if the account has rows and none of them is in the store
   */
  public ImportResult importFiles(long userId, String startedBy) throws IOException {
    User user = users.findById(userId).orElseThrow(AccountService::accountNotFound);
    Home home = storagePaths.home(user);
    // The rows first: one committed while the store is walked then has its item in the walk, and
    // an item that gets a row meanwhile is skipped when its row is added.
    Set<String> tracked = new HashSet<>(files.findPathsOf(user.getId()));
    Walk walk = walk(home);

    Set<String> storedKeys = new HashSet<>();
    walk.items().forEach(item -> storedKeys.add(item.key()));
    List<String> missing = tracked.stream().filter(path -> !storedKeys.contains(path)).toList();
    if (!tracked.isEmpty() && missing.size() == tracked.size()) {
      log.warn(
          "{} started an import for account {}, stopped: none of its {} rows is in {}",
          startedBy,
          user.getId(),
          tracked.size(),
          store.description());
      throw new ConflictException(
          "None of this account's files are in the store, which looks like the wrong serving"
              + " directory or bucket. Nothing was imported.");
    }

    List<Item> untracked =
        walk.items().stream().filter(item -> !tracked.contains(item.key())).toList();
    Set<String> imported = new HashSet<>();
    List<Added> added = new ArrayList<>();
    try {
      for (int start = 0; start < untracked.size(); start += BATCH_SIZE) {
        List<Item> batch = untracked.subList(start, Math.min(start + BATCH_SIZE, untracked.size()));
        added.addAll(
            Objects.requireNonNull(transactions.execute(status -> add(home, batch, imported))));
      }
      added.removeAll(vanished(home, added));
    } catch (IOException | RuntimeException e) {
      // The rows added so far stay, and are kept by the next import.
      log.warn(
          "Import for account {} failed after adding {} rows of {}",
          user.getId(),
          added.size(),
          untracked.size());
      throw e;
    }
    int importedFolders = (int) added.stream().filter(a -> a.item().entry().isDirectory()).count();
    int importedFiles = added.size() - importedFolders;

    log.info(
        "{} imported {} files and {} folders for account {}",
        startedBy,
        importedFiles,
        importedFolders,
        user.getId());
    if (!missing.isEmpty()) {
      // Ids rather than paths, so the log says which rows without saying what the files are.
      log.warn(
          "Account {} has {} rows with nothing in the store, ids {}",
          user.getId(),
          missing.size(),
          files.findIdsOf(user.getId(), missing.stream().limit(MISSING_IDS_LOGGED).toList()));
    }
    if (walk.skipped() > 0) {
      log.warn(
          "Left {} items in account {}'s folder out of the import: names the app doesn't accept",
          walk.skipped(),
          user.getId());
    }
    return new ImportResult(importedFiles, importedFolders, missing.size(), walk.skipped());
  }

  // What the account's folder holds, folders before what they hold. Hidden items are left out,
  // as the file tree leaves them out: the app's scratch files, and files the system put there. So
  // is an item whose name the app wouldn't accept from a client, such as one too long to keep or
  // with a backslash, with everything in it.
  private Walk walk(Home home) throws IOException {
    String top = home.key();
    String prefix = top + "/";
    List<Item> items = new ArrayList<>();
    int[] skipped = {0};
    store.walk(
        top,
        entry -> {
          if (entry.key().equals(top)) {
            return FileVisitResult.CONTINUE;
          }
          if (entry.name().startsWith(".")) {
            return FileVisitResult.SKIP_SUBTREE;
          }
          if (!StoragePaths.isAcceptableName(entry.name())
              || entry.key().length() - prefix.length() > MAX_PATH_LENGTH) {
            skipped[0]++;
            return FileVisitResult.SKIP_SUBTREE;
          }
          items.add(new Item(entry.key().substring(prefix.length()), entry));
          return FileVisitResult.CONTINUE;
        });
    return new Walk(items, skipped[0]);
  }

  // Rows for the items of one batch. An item that got a row since the account's rows were read,
  // from an upload or another import, keeps that one. Search is told about the topmost items
  // imported, each of which it reads with everything below it.
  private List<Added> add(Home home, List<Item> batch, Set<String> imported) {
    Instant now = Instant.now();
    List<Added> added = new ArrayList<>();
    for (Item item : batch) {
      Entry entry = item.entry();
      Instant modified = entry.modified() != null ? entry.modified() : now;
      Instant created = entry.created() != null ? entry.created() : modified;
      boolean inserted =
          rows.addIfAbsent(
              home.userId(),
              item.key(),
              entry.name(),
              entry.isDirectory() ? 0 : entry.size(),
              entry.isDirectory(),
              created,
              modified);
      if (inserted) {
        long id = files.findByPath(home.userId(), item.key()).orElseThrow().getId();
        added.add(new Added(item, id));
        imported.add(item.key());
        if (!imported.contains(parentKey(item.key()))) {
          searchIndex.changed(home, item.key());
        }
      }
    }
    return added;
  }

  // A delete that ran while a batch was added doesn't see its rows, which weren't committed yet,
  // and leaves them describing nothing. Now they are committed, the store is walked again, and a
  // row added here whose item isn't there any more is locked and looked at once more: a request
  // replacing the item holds that lock while the item is briefly gone (archived, the new content
  // not yet in place), so this waits for it rather than taking that for a delete.
  private List<Added> vanished(Home home, List<Added> added) throws IOException {
    if (added.isEmpty()) {
      return List.of();
    }
    Set<String> present = new HashSet<>();
    walk(home).items().forEach(item -> present.add(item.key()));
    List<Added> gone = new ArrayList<>();
    for (Added candidate : added) {
      if (present.contains(candidate.item().key())) {
        continue;
      }
      boolean removed =
          Boolean.TRUE.equals(
              transactions.execute(
                  status -> {
                    Optional<FileMetadata> row = files.lockById(candidate.id());
                    if (row.isEmpty()) {
                      return true;
                    }
                    if (exists(candidate.item().entry().key())) {
                      return false;
                    }
                    versions.discardAll(row.get());
                    quota.add(home.userId(), -row.get().countedBytes());
                    files.delete(row.get());
                    return true;
                  }));
      if (removed) {
        gone.add(candidate);
      }
    }
    return gone;
  }

  private boolean exists(String key) {
    try {
      return store.exists(key);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String parentKey(String key) {
    int slash = key.lastIndexOf('/');
    return slash < 0 ? "" : key.substring(0, slash);
  }

  private record Walk(List<Item> items, int skipped) {}

  /** An item this import gave a row, and the row's id. */
  private record Added(Item item, long id) {}

  /** An item in the account's folder, with its key relative to the folder. */
  private record Item(String key, Entry entry) {}
}
