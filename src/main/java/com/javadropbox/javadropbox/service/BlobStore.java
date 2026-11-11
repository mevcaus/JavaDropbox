package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.service.FileStore.Entry;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps each distinct content in the {@link FileStore} under its SHA-256, at {@code .blobs/<first 2
 * hex>/<hash>}. The hash is always computed from the content as it is written, never taken from the
 * caller (a hash recorded on a row is only a hint, see #315), so a blob always holds the bytes its
 * name says.
 *
 * <p>Writing is in two steps: {@link #stage} writes the content to a scratch file and hashes it,
 * and {@link #place} puts it in place as its blob if no blob with that hash is there, so that the
 * code counting references (#316) can put a blob back in place under the same lock as the code
 * deleting it. A staged file that wasn't needed is dropped with {@link #discard}. Nothing calls
 * this yet.
 */
@Component
public class BlobStore {

  private static final Logger log = LoggerFactory.getLogger(BlobStore.class);

  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  private final FileStore store;

  public BlobStore(FileStore store) {
    this.store = store;
  }

  /**
   * A content written to a scratch file and hashed, not yet a blob. Only {@link #stage} makes one,
   * so its hash is always the one computed from its bytes.
   */
  public static final class Staged {

    private final String hash;
    private final long size;
    private final String scratch;

    private Staged(String hash, long size, String scratch) {
      this.hash = hash;
      this.size = size;
      this.scratch = scratch;
    }

    /** Its SHA-256, as 64 lowercase hex digits. */
    public String hash() {
      return hash;
    }

    /** Its size in bytes. */
    public long size() {
      return size;
    }

    String scratch() {
      return scratch;
    }
  }

  /**
   * Writes {@code content} to a scratch file beside the blobs, hashing it as it goes.
   *
   * @param length how many bytes {@code content} holds, or -1 if that is not known
   */
  public Staged stage(InputStream content, long length) throws IOException {
    store.createFolders(StoragePaths.BLOBS_DIR);
    String scratch = store.scratchBeside(StoragePaths.BLOBS_DIR + "/incoming");
    try (HashingInputStream in = new HashingInputStream(content)) {
      long size = store.write(scratch, in, length);
      if (in.count() != size) {
        throw new IllegalStateException(
            "Hashed " + in.count() + " bytes of content the store wrote " + size + " of");
      }
      return new Staged(in.hex(), size, scratch);
    } catch (IOException | RuntimeException e) {
      discard(scratch);
      throw e;
    }
  }

  /**
   * Makes {@code staged} its blob, unless a blob with its hash and size is already there; either
   * way the blob is there afterwards. Two contents with one hash are the same bytes, so a blob put
   * in place meanwhile by someone else is as good as this one. One of another size was left torn by
   * a crash, since the local disk isn't synced, and is replaced.
   */
  void place(Staged staged) throws IOException {
    String key = key(staged.hash());
    Optional<Entry> existing = store.stat(key);
    if (existing.isPresent() && existing.get().size() == staged.size()) {
      return;
    }
    store.createFolders(key.substring(0, key.lastIndexOf('/')));
    store.replace(staged.scratch(), key);
  }

  /** Drops a staged file, if it is still there. Never throws: what's left is swept at startup. */
  public void discard(Staged staged) {
    discard(staged.scratch());
  }

  private void discard(String scratch) {
    try {
      store.delete(scratch);
    } catch (IOException | RuntimeException e) {
      log.warn("Could not remove {}; the startup sweep will: {}", scratch, e.toString());
    }
  }

  /** Reads the blob with this hash. */
  public InputStream open(String hash) throws IOException {
    return store.open(key(hash));
  }

  /** The blob with this hash, as the store has it, or empty if it isn't there. */
  public Optional<Entry> stat(String hash) throws IOException {
    return store.stat(key(hash));
  }

  /** Deletes the blob with this hash, once nothing refers to it (#316). */
  void delete(String hash) throws IOException {
    store.delete(key(hash));
  }

  /**
   * Where the blob with this hash is in the store.
   *
   * @throws IllegalArgumentException if {@code hash} isn't 64 lowercase hex digits
   */
  static String key(String hash) {
    if (hash == null || !HASH.matcher(hash).matches()) {
      throw new IllegalArgumentException("Not a SHA-256 in hex: " + hash);
    }
    return StoragePaths.BLOBS_DIR + "/" + hash.substring(0, 2) + "/" + hash;
  }
}
