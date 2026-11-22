package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.model.FileVersion;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.service.FileStore.Entry;
import com.javadropbox.javadropbox.service.StoragePaths.StoragePath;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Where a file's or a version's content is in the {@link FileStore}: the one place readers ask, so
 * that reading it doesn't depend on how it is kept. Download, preview, share links and restores
 * read through here, and so do folder zips and search, each file in turn.
 *
 * <p>A row that names a blob (#316) has its content there, under {@code .blobs/}. One that doesn't
 * has it at its own path: a file's in its account's folder, a version's under {@code .versions/}.
 * So does a file with no row, copied in by hand and not imported yet. Folders are always at their
 * paths. Nothing makes a row name a blob yet; uploads switch to blobs in the last part of #316.
 *
 * <p>What this finds is for reading only. A blob is shared by every file and version that has the
 * same content, so an entry found here is never moved, replaced or deleted: copy it instead.
 *
 * <p>Not yet here: checks made before a write (whether something is at a path), which change with
 * the writes.
 */
@Component
public class FileContents {

  private static final Logger log = LoggerFactory.getLogger(FileContents.class);

  /** Finds the blob an account's file at a path is kept as; see {@link #of(StoragePath)}. */
  @FunctionalInterface
  interface BlobLookup {
    Optional<String> blobHash(long ownerId, String path);
  }

  private final FileStore store;
  private final BlobLookup rows;

  @Autowired
  public FileContents(FileStore store, FileMetadataRepository files) {
    this(store, files::findBlobHash);
  }

  FileContents(FileStore store, BlobLookup rows) {
    this.store = store;
    this.rows = rows;
  }

  /**
   * What is at {@code item}: a file's content, wherever its row says it is kept, or a folder. Empty
   * if nothing is there, including a blob its row names that has gone from the store.
   */
  public Optional<Entry> of(StoragePath item) throws IOException {
    // The account's root has no row of its own. The row is looked up by the account the item is
    // in, so a path never finds another account's file.
    Optional<String> blob =
        item.isRoot() ? Optional.empty() : rows.blobHash(item.home().userId(), item.key());
    return of(item, blob.orElse(null));
  }

  /**
   * Like {@link #of(StoragePath)}, with the blob the item's row names already known, as a listing
   * of rows has it, so reading many files needs no query for each.
   *
   * @param blobHash as {@code item}'s own row has it, never from anywhere else: null where its
   *     content is at its own path
   */
  Optional<Entry> of(StoragePath item, String blobHash) throws IOException {
    if (blobHash == null) {
      return store.stat(item.storeKey());
    }
    return blob(blobHash, item.storeKey());
  }

  /** A version's stored copy, wherever its row says it is kept, or empty if it isn't there. */
  public Optional<Entry> ofVersion(FileVersion version) throws IOException {
    String blobHash = version.getBlobHash();
    if (blobHash == null) {
      return store.stat(versionKey(version.getStoredFilename()));
    }
    return blob(blobHash, "version " + version.getId());
  }

  // A blob a row names that has gone is a bug or a race with deleting it, never the user's doing,
  // and it would otherwise only show as a 404 or a file missing from a zip.
  private Optional<Entry> blob(String hash, String whose) throws IOException {
    Optional<Entry> blob = store.stat(BlobStore.key(hash));
    if (blob.isEmpty()) {
      log.warn("Blob {} of {} is missing from the store", hash, whose);
    }
    return blob;
  }

  /**
   * Content found here, to read; see {@link FileStore#open}.
   *
   * @param content as {@link #of} or {@link #ofVersion} found it
   */
  public InputStream open(Entry content) throws IOException {
    return store.open(content.key());
  }

  /**
   * Content found here, read as a local file; see {@link FileStore#readLocally}.
   *
   * @param content as {@link #of} or {@link #ofVersion} found it
   */
  public <T> T readLocally(Entry content, FileStore.LocalReader<T> reader) throws IOException {
    return store.readLocally(content.key(), reader);
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
