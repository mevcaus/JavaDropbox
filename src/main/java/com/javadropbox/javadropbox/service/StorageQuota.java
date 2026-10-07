package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.exception.InsufficientStorageException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * An optional cap on everything the serving directory holds, previous versions included, set with
 * {@code javadropbox.storage.max-total-size} (e.g. {@code 50MB}). Unset, there is no cap. The app's
 * own state in {@code .javadropbox}, such as the search index, does not count: nobody stored it.
 *
 * <p>Usage is measured by walking the directory, which is what the disk actually holds whatever the
 * database thinks. That is cheap at the sizes a cap is meant for, such as the public demo's.
 */
@Component
public class StorageQuota {

  private final StoragePaths storagePaths;
  private final DataSize limit;

  public StorageQuota(
      StoragePaths storagePaths, @Value("${javadropbox.storage.max-total-size:}") String limit) {
    this.storagePaths = storagePaths;
    this.limit = limit.isBlank() ? null : DataSize.parse(limit.trim());
    if (this.limit != null && this.limit.toBytes() <= 0) {
      throw new IllegalStateException(
          "javadropbox.storage.max-total-size must be more than 0, not " + limit);
    }
  }

  public Optional<DataSize> limit() {
    return Optional.ofNullable(limit);
  }

  /**
   * Refuses to go on if the serving directory now holds more than the cap. Called with the new
   * content already written to its scratch file, which the walk counts, so the check covers it.
   *
   * @throws InsufficientStorageException if over the cap
   */
  void check() throws IOException {
    if (limit != null && usedBytes() > limit.toBytes()) {
      throw new InsufficientStorageException(
          "Not enough storage space: this server holds at most "
              + describe(limit)
              + " of files, previous versions included. Delete something to make room.");
    }
  }

  /**
   * The bytes in every regular file under the serving directory, outside the app's own state.
   * Symlinks are not followed.
   */
  public long usedBytes() throws IOException {
    long[] total = {0};
    Path internal = storagePaths.internalDir();
    Files.walkFileTree(
        storagePaths.versionsDir().getParent(),
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return dir.equals(internal) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile()) {
              total[0] += attrs.size();
            }
            return FileVisitResult.CONTINUE;
          }

          // A file deleted while the walk is under way simply no longer counts.
          @Override
          public FileVisitResult visitFileFailed(Path file, IOException e) {
            return FileVisitResult.CONTINUE;
          }
        });
    return total[0];
  }

  static String describe(DataSize size) {
    long bytes = size.toBytes();
    if (bytes % DataSize.ofMegabytes(1).toBytes() == 0) {
      return size.toMegabytes() + " MB";
    }
    if (bytes % DataSize.ofKilobytes(1).toBytes() == 0) {
      return size.toKilobytes() + " KB";
    }
    return bytes + " bytes";
  }
}
