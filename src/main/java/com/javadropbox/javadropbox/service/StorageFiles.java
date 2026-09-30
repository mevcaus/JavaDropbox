package com.javadropbox.javadropbox.service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Filesystem operations shared by the storage services. Each checks its path again with {@link
 * StoragePaths#recheck} right before touching it.
 */
final class StorageFiles {

  private StorageFiles() {}

  /**
   * Deletes a file or a folder and everything in it. Symlinks are deleted themselves, never
   * followed, so a link inside the folder cannot take files outside it down with it.
   */
  static void deleteRecursively(Path path) throws IOException {
    StoragePaths.recheck(path);
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      Files.deleteIfExists(path);
      return;
    }
    Files.walkFileTree(
        path,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            // The file itself may be a link, which is deleted rather than followed.
            StoragePaths.recheck(file.getParent());
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (exc != null) {
              throw exc;
            }
            Files.delete(StoragePaths.recheck(dir));
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /** Replaces {@code target} with {@code source} in one step where the filesystem allows it. */
  static void moveIntoPlace(Path source, Path target) throws IOException {
    // A rename replaces a link at the target itself, but follows one along its folders.
    StoragePaths.recheck(target.getParent());
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** A hidden scratch file next to {@code target}, so moving it into place is a rename. */
  static Path tempFileBeside(Path target) throws IOException {
    return Files.createTempFile(StoragePaths.recheck(target.getParent()), ".upload-", ".tmp");
  }
}
