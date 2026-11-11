package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Gives an item found in the store a row, unless it has one, and counts its bytes against its
 * owner's storage when the row is new: the one way an item already stored gets a row, so none of
 * them forgets the second half.
 */
@Component
class FileRows {

  private final FileMetadataRepository files;
  private final StorageQuota quota;

  FileRows(FileMetadataRepository files, StorageQuota quota) {
    this.files = files;
    this.quota = quota;
  }

  /**
   * Adds the row, waiting for anyone adding one at the same path meanwhile; see {@link
   * FileMetadataRepository#insertIfAbsent}. Must run in a transaction.
   *
   * @return whether this added it
   */
  boolean addIfAbsent(
      long ownerId,
      String path,
      String filename,
      long size,
      boolean isDirectory,
      Instant created,
      Instant updated) {
    boolean added =
        files.insertIfAbsent(ownerId, path, filename, size, isDirectory, created, updated) == 1;
    if (added && !isDirectory) {
      quota.add(ownerId, size);
    }
    return added;
  }
}
