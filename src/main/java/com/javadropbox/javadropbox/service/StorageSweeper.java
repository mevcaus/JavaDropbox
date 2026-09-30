package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.FileVersionRepository;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
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
 * Clears up what interrupted operations leave on disk once the app has started: upload scratch
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

  private final StoragePaths storagePaths;
  private final FileMetadataRepository files;
  private final FileVersionRepository versions;

  public StorageSweeper(
      StoragePaths storagePaths, FileMetadataRepository files, FileVersionRepository versions) {
    this.storagePaths = storagePaths;
    this.files = files;
    this.versions = versions;
  }

  /** Removes stale leftovers. Never throws: a failed clean-up must not stop the app. */
  @EventListener(ApplicationReadyEvent.class)
  public void sweep() {
    FileTime cutoff = FileTime.from(Instant.now().minus(MIN_AGE));
    try {
      removeScratchFiles(cutoff);
      if (Files.isDirectory(storagePaths.versionsDir())) {
        removeUnreferencedVersions(cutoff);
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Could not clean up leftovers in the storage", e);
    }
  }

  private void removeScratchFiles(FileTime cutoff) throws IOException {
    Path root = storagePaths.root();
    Set<Path> ownDirs = Set.of(storagePaths.versionsDir(), storagePaths.internalDir());
    Files.walkFileTree(
        root,
        new Sweep() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return ownDirs.contains(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            String name = file.getFileName().toString();
            if (name.startsWith(".upload-")
                && name.endsWith(".tmp")
                && attrs.lastModifiedTime().compareTo(cutoff) < 0) {
              remove(file);
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /**
   * Removes stored versions only where they are provably orphaned: unreferenced files in the folder
   * of a file the database knows. Everything else is left alone, since the database might not be
   * the one this storage belongs to (a new volume, a wrong datasource URL, a reinstall); trusting
   * it would delete every version on disk.
   */
  private void removeUnreferencedVersions(FileTime cutoff) throws IOException {
    Path store = storagePaths.versionsDir();
    Set<String> knownIds =
        files.findAllIds().stream().map(String::valueOf).collect(Collectors.toSet());
    if (knownIds.isEmpty()) {
      if (!isEmpty(store)) {
        log.warn(
            "Not cleaning up {}: the database has no files, so it may not be the database this"
                + " storage belongs to",
            store);
      }
      return;
    }
    Set<Path> referenced =
        versions.findAllStoredFilenames().stream()
            .map(stored -> store.resolve(stored).normalize())
            .collect(Collectors.toSet());

    int unknownFolders = 0;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(store)) {
      for (Path entry : entries) {
        BasicFileAttributes attrs =
            Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        // Loose files are the old <name>.v<n> layout, which doesn't say whose version it is.
        if (!attrs.isDirectory()) {
          continue;
        }
        if (knownIds.contains(entry.getFileName().toString())) {
          removeUnreferencedFiles(entry, referenced, cutoff);
        } else if (attrs.lastModifiedTime().compareTo(cutoff) < 0 && isEmpty(entry)) {
          remove(entry);
        } else {
          unknownFolders++;
        }
      }
    }
    if (unknownFolders > 0) {
      log.warn(
          "Left {} folders in {} alone: the database knows no file with their ids",
          unknownFolders,
          store);
    }
  }

  private void removeUnreferencedFiles(Path folder, Set<Path> referenced, FileTime cutoff)
      throws IOException {
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
      for (Path file : entries) {
        BasicFileAttributes attrs =
            Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isRegularFile()
            && !referenced.contains(file)
            && attrs.lastModifiedTime().compareTo(cutoff) < 0) {
          remove(file);
        }
      }
    }
  }

  private static boolean isEmpty(Path dir) {
    try (var entries = Files.list(dir)) {
      return entries.findAny().isEmpty();
    } catch (IOException e) {
      return false;
    }
  }

  private static void remove(Path path) {
    try {
      Files.delete(path);
      log.info("Removed {}, left over from an interrupted operation", path);
    } catch (IOException e) {
      log.warn("Could not remove leftover {}: {}", path, e.toString());
    }
  }

  /** Walks past anything it cannot read instead of giving up on the rest. */
  private static class Sweep extends SimpleFileVisitor<Path> {
    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
      log.warn("Could not check {} for leftovers: {}", file, exc.toString());
      return FileVisitResult.CONTINUE;
    }
  }
}
