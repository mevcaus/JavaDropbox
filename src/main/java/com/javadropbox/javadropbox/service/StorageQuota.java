package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.exception.InsufficientStorageException;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileVersionRepository;
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
 * Caps on what is stored, previous versions included. Two can apply, each only if it is set:
 *
 * <ul>
 *   <li>an account's quota, which an admin gives it ({@link User#getQuotaBytes});
 *   <li>a cap on everything the serving directory holds, whoever stored it, set with {@code
 *       javadropbox.storage.max-total-size} (e.g. {@code 50MB}).
 * </ul>
 *
 * <p>The app's own state in {@code .javadropbox}, such as the search index, never counts: nobody
 * stored it. Usage is measured by walking the directory, which is what the disk actually holds
 * whatever the database thinks; an account's previous versions are counted from their rows, since
 * the version store is shared. That is cheap at the sizes a cap is meant for, such as the public
 * demo's.
 */
@Component
public class StorageQuota {

  /** What an account stores, previous versions included, and its quota (null for none). */
  public record Usage(long usedBytes, Long quotaBytes) {}

  private final StoragePaths storagePaths;
  private final FileVersionRepository versions;
  private final DataSize limit;

  public StorageQuota(
      StoragePaths storagePaths,
      FileVersionRepository versions,
      @Value("${javadropbox.storage.max-total-size:}") String limit) {
    this.storagePaths = storagePaths;
    this.versions = versions;
    this.limit = limit.isBlank() ? null : DataSize.parse(limit.trim());
    if (this.limit != null && this.limit.toBytes() <= 0) {
      throw new IllegalStateException(
          "javadropbox.storage.max-total-size must be more than 0, not " + limit);
    }
  }

  /** The cap on the whole serving directory, if there is one. */
  public Optional<DataSize> limit() {
    return Optional.ofNullable(limit);
  }

  public Usage usage(User user) throws IOException {
    return new Usage(usedBytes(user), user.getQuotaBytes());
  }

  /**
   * Refuses to go on if {@code user} now stores more than their quota, or the serving directory
   * holds more than its cap. Called with the new content already written to its scratch file in the
   * account's folder, which the walks count, so the check covers it.
   *
   * @throws InsufficientStorageException if over either
   */
  void check(User user) throws IOException {
    Long quota = user.getQuotaBytes();
    if (quota != null && usedBytes(user) > quota) {
      throw new InsufficientStorageException(
          "Not enough storage space: your account can hold at most "
              + describe(DataSize.ofBytes(quota))
              + " of files, previous versions included. Delete something to make room.");
    }
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
    return walk(storagePaths.versionsDir().getParent(), storagePaths.internalDir());
  }

  /** The bytes in an account's folder, plus those of its files' previous versions. */
  public long usedBytes(User user) throws IOException {
    return walk(storagePaths.home(user).root(), null) + versions.totalSizeOf(user.getId());
  }

  private static long walk(Path start, Path skip) throws IOException {
    long[] total = {0};
    Files.walkFileTree(
        start,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return dir.equals(skip) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
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
    if (bytes % DataSize.ofGigabytes(1).toBytes() == 0) {
      return size.toGigabytes() + " GB";
    }
    if (bytes % DataSize.ofMegabytes(1).toBytes() == 0) {
      return size.toMegabytes() + " MB";
    }
    if (bytes % DataSize.ofKilobytes(1).toBytes() == 0) {
      return size.toKilobytes() + " KB";
    }
    return bytes + " bytes";
  }
}
