package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.FileVersionRepository;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Clears up what interrupted operations leave on disk once the app has started: upload scratch
 * files, and stored versions or version folders that no row refers to (a crash between moving a
 * file and committing, or a clean-up after commit that failed).
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

  private void removeUnreferencedVersions(FileTime cutoff) throws IOException {
    Path store = storagePaths.versionsDir();
    Set<Path> referenced =
        versions.findAllStoredFilenames().stream()
            .map(stored -> store.resolve(stored).normalize())
            .collect(Collectors.toSet());
    Set<Path> knownFolders =
        files.findAllIds().stream()
            .map(id -> store.resolve(String.valueOf(id)))
            .collect(Collectors.toSet());
    // Removing files updates a folder's modification time, so judge folders by it beforehand.
    Set<Path> staleFolders = new HashSet<>();

    Files.walkFileTree(
        store,
        new Sweep() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (!dir.equals(store) && attrs.lastModifiedTime().compareTo(cutoff) < 0) {
              staleFolders.add(dir);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (!referenced.contains(file) && attrs.lastModifiedTime().compareTo(cutoff) < 0) {
              remove(file);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            if (staleFolders.contains(dir) && !knownFolders.contains(dir) && isEmpty(dir)) {
              remove(dir);
            }
            return FileVisitResult.CONTINUE;
          }
        });
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
