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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * Each account's quota: the most it may store, previous versions included, which an admin gives it
 * ({@link User#getQuotaBytes}); an account without one may store anything the disk holds.
 *
 * <p>Usage is measured by walking the account's folder, which is what the disk actually holds
 * whatever the database thinks, plus the sizes of its files' previous versions from their rows,
 * since the version store is shared. That is cheap at the sizes quotas are meant for, such as the
 * public demo's.
 *
 * <p>There used to be a second cap, on everything the serving directory held, which only the demo
 * set; the demo account's quota does that job now (see DemoService). A server still configured with
 * it refuses to start, rather than quietly running without the cap its owner expects.
 */
@Component
public class StorageQuota {

  /** The setting the server-wide cap was read from. */
  static final String REMOVED_CAP = "javadropbox.storage.max-total-size";

  /** What an account stores, previous versions included, and its quota (null for none). */
  public record Usage(long usedBytes, Long quotaBytes) {}

  private final StoragePaths storagePaths;
  private final FileVersionRepository versions;

  public StorageQuota(
      StoragePaths storagePaths,
      FileVersionRepository versions,
      @Value("${" + REMOVED_CAP + ":}") String removedCap) {
    if (!removedCap.isBlank()) {
      throw new IllegalStateException(
          REMOVED_CAP
              + " is no longer supported: give each account a quota in the app instead (Users,"
              + " see docs/self-hosting.md), then remove the setting.");
    }
    this.storagePaths = storagePaths;
    this.versions = versions;
  }

  public Usage usage(User user) throws IOException {
    return new Usage(usedBytes(user), user.getQuotaBytes());
  }

  /**
   * Refuses to go on if {@code user} now stores more than their quota. Called with the new content
   * already written to its scratch file in the account's folder, which the walk counts, so the
   * check covers it.
   *
   * @throws InsufficientStorageException if over it
   */
  void check(User user) throws IOException {
    Long quota = user.getQuotaBytes();
    if (quota != null && usedBytes(user) > quota) {
      throw new InsufficientStorageException(
          "Not enough storage space: your account can hold at most "
              + describe(DataSize.ofBytes(quota))
              + " of files, previous versions included. Delete something to make room.");
    }
  }

  /**
   * The bytes in an account's folder, plus those of its files' previous versions. Symlinks are not
   * followed.
   */
  public long usedBytes(User user) throws IOException {
    long[] total = {0};
    Files.walkFileTree(
        storagePaths.home(user).root(),
        new SimpleFileVisitor<>() {
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
    return total[0] + versions.totalSizeOf(user.getId());
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
