package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.model.FileVersion;
import com.javadropbox.javadropbox.service.FileStore.Entry;
import com.javadropbox.javadropbox.service.StoragePaths.StoragePath;
import java.io.IOException;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Where a file's or a version's content is in the {@link FileStore}: the one place readers ask, so
 * that reading it doesn't depend on how it is kept. Download, preview, share links and restores
 * read through here. Today a file's content is at its path in its account's folder and a version's
 * under {@code .versions/}; once files are blobs (#316), {@link #of} looks up the item's row (by
 * the account its path is in) and returns the blob it points at, and {@link #ofVersion} does the
 * same with the version's row. Folders stay at their paths.
 *
 * <p>Not yet here: checks made before a write (whether something is at a path), and search's text
 * and hits, which #394 moves onto rows.
 */
@Component
public class FileContents {

  private final FileStore store;

  public FileContents(FileStore store) {
    this.store = store;
  }

  /**
   * What is at {@code item}: a file's content, or a folder, which is always at its path. Empty if
   * nothing is there.
   */
  public Optional<Entry> of(StoragePath item) throws IOException {
    return store.stat(item.storeKey());
  }

  /** A version's stored copy, or empty if it isn't there. */
  public Optional<Entry> ofVersion(FileVersion version) throws IOException {
    return store.stat(versionKey(version.getStoredFilename()));
  }

  /**
   * Content found here, to send as a response; see {@link FileStore#resource}.
   *
   * @param content as {@link #of} or {@link #ofVersion} found it
   */
  public Resource resource(Entry content) throws IOException {
    return store.resource(content);
  }

  /**
   * Where a version's stored copy is kept today, by the name its row records. Archiving and
   * deleting versions use it too; when versions become blobs, those change with it, not only the
   * readers.
   *
   * @throws IllegalStateException if the name would lead outside the version store: rows are
   *     written by the app, but a row pointing anywhere else would be the store's to lose
   */
  static String versionKey(String storedFilename) {
    for (String segment : storedFilename.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalStateException("Version row points outside the version store");
      }
    }
    return StoragePaths.VERSIONS_DIR + "/" + storedFilename;
  }
}
