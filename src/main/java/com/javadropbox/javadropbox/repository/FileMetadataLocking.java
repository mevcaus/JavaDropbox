package com.javadropbox.javadropbox.repository;

import com.javadropbox.javadropbox.model.FileMetadata;
import java.util.Optional;

/**
 * Row locks for changes that read a file's row and then write based on it, such as archiving the
 * live file as the next version. Concurrent changes to one file take turns instead of both acting
 * on the same state.
 */
public interface FileMetadataLocking {

  /**
   * The row, locked until the transaction ends and freshly read after locking.
   *
   * @return empty if there is no such row, including when it was deleted while waiting for the lock
   */
  Optional<FileMetadata> lockById(Long id);

  /** Like {@link #lockById}, for the row at a path. */
  Optional<FileMetadata> lockByPath(String path);
}
