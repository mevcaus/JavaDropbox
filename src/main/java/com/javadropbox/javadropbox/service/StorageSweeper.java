package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.FileVersionRepository;
import com.javadropbox.javadropbox.service.FileStore.Entry;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Clears up what interrupted operations leave in the store once the app has started: upload scratch
 * files, and stored versions of known files that no row refers to (a crash between moving a file
 * and committing, or a clean-up after commit that failed).
 *
 * <p>Requests are already being served by then, and other instances may share the storage, so
 * anything modified within the last hour is left alone: a request could be writing it, or be about
 * to commit the row that refers to it. Archiving touches each stored copy for this reason.
 */
@Component
public class StorageSweeper {

  static final Duration MIN_AGE = Duration.ofHours(1);

  private static final Logger log = LoggerFactory.getLogger(StorageSweeper.class);

  private final FileStore store;
  private final FileMetadataRepository files;
  private final FileVersionRepository versions;

  public StorageSweeper(
      FileStore store, FileMetadataRepository files, FileVersionRepository versions) {
    this.store = store;
    this.files = files;
    this.versions = versions;
  }

  /** Removes stale leftovers. Never throws: a failed clean-up must not stop the app. */
  @EventListener(ApplicationReadyEvent.class)
  public void sweep() {
    Instant cutoff = Instant.now().minus(MIN_AGE);
    try {
      removeScratchFiles(cutoff);
      if (store.stat(StoragePaths.VERSIONS_DIR).map(Entry::isDirectory).orElse(false)) {
        removeUnreferencedVersions(cutoff);
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Could not clean up leftovers in the storage", e);
    }
  }

  private void removeScratchFiles(Instant cutoff) throws IOException {
    Set<String> ownDirs = Set.of(StoragePaths.VERSIONS_DIR, StoragePaths.INTERNAL_DIR);
    store.walk(
        "",
        entry -> {
          if (entry.isDirectory()) {
            return ownDirs.contains(entry.key())
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }
          String name = entry.name();
          if (name.startsWith(".upload-") && name.endsWith(".tmp") && isOlder(entry, cutoff)) {
            remove(entry);
          }
          return FileVisitResult.CONTINUE;
        });
  }

  /**
   * Removes stored versions only where they are provably orphaned: unreferenced files in the folder
   * of a file the database knows. Everything else is left alone, since the database might not be
   * the one this storage belongs to (a new volume, a wrong datasource URL, a reinstall); trusting
   * it would delete every version in the store.
   */
  private void removeUnreferencedVersions(Instant cutoff) throws IOException {
    String versionsDir = StoragePaths.VERSIONS_DIR;
    Set<String> knownIds =
        files.findAllIds().stream().map(String::valueOf).collect(Collectors.toSet());
    if (knownIds.isEmpty()) {
      if (!isEmpty(versionsDir)) {
        log.warn(
            "Not cleaning up {}: the database has no files, so it may not be the database this"
                + " storage belongs to",
            versionsDir);
      }
      return;
    }
    Set<String> referenced =
        versions.findAllStoredFilenames().stream()
            .map(stored -> versionsDir + "/" + stored)
            .collect(Collectors.toSet());

    int unknownFolders = 0;
    for (Entry entry : store.list(versionsDir)) {
      // Loose files are the old <name>.v<n> layout, which doesn't say whose version it is.
      if (!entry.isDirectory()) {
        continue;
      }
      if (knownIds.contains(entry.name())) {
        removeUnreferencedFiles(entry, referenced, cutoff);
      } else if (isOlder(entry, cutoff) && isEmpty(entry.key())) {
        remove(entry);
      } else {
        unknownFolders++;
      }
    }
    if (unknownFolders > 0) {
      log.warn(
          "Left {} folders in {} alone: the database knows no file with their ids",
          unknownFolders,
          versionsDir);
    }
  }

  private void removeUnreferencedFiles(Entry folder, Set<String> referenced, Instant cutoff)
      throws IOException {
    for (Entry file : store.list(folder.key())) {
      if (!file.isDirectory() && !referenced.contains(file.key()) && isOlder(file, cutoff)) {
        remove(file);
      }
    }
  }

  // A store that does not know when a folder changed, as S3 does not, has nothing else to go by.
  // Removing an empty folder there never takes anything put in it meanwhile (see remove).
  private static boolean isOlder(Entry entry, Instant cutoff) {
    return entry.modified() == null || entry.modified().isBefore(cutoff);
  }

  private boolean isEmpty(String folder) {
    try {
      return store.list(folder).isEmpty();
    } catch (NoSuchFileException e) {
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  // Only ever a file or an empty folder: a folder something was put in since is left as it is.
  private void remove(Entry entry) {
    try {
      store.delete(entry.key());
      log.info("Removed {}, left over from an interrupted operation", entry.key());
    } catch (IOException e) {
      log.warn("Could not remove leftover {}: {}", entry.key(), e.toString());
    }
  }
}
