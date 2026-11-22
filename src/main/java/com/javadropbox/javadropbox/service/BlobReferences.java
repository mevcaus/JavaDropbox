package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.service.BlobStore.Staged;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Counts the files and versions that point at each blob (#316), so that a blob is kept while any do
 * and deleted once none do. Nothing calls this yet; uploads switch to blobs in the last part of
 * #316, which also points rows at blobs, in the same transaction as the count changes here.
 *
 * <p>How content becomes a reference:
 *
 * <ol>
 *   <li>{@link #prepare}, outside any transaction, stages the content and hashes it. Unless the
 *       uploading account already holds that content, it puts it in place as its blob, with the
 *       same copy whether or not another account holds it, so how long an upload takes says nothing
 *       about anyone else's files (decided on #316, as for sync in #202). Before that, it claims
 *       the blob under its lock for a moment, so it isn't deleted meanwhile (see {@link #claim}).
 *   <li>In the transaction, {@link #lock} takes the lock of every blob it will add or remove a
 *       reference to, before touching any row.
 *   <li>{@link #add} checks again, under the lock, whether the account holds the content, puts the
 *       blob back if it went meanwhile, and counts the reference. {@link #remove} takes one off.
 *   <li>Afterwards, {@link #discard} drops what was staged and not used.
 * </ol>
 *
 * <p>A blob whose count falls to zero is deleted by {@link BlobCollector}, under the same lock,
 * once it has stayed at zero for the grace period. Whoever is about to put a blob in place claims
 * it under that lock first, and whoever deletes one takes it too, so a blob can't be deleted under
 * a new reference.
 */
@Service
public class BlobReferences {

  /**
   * The key space of the blobs' advisory locks. PostgreSQL keeps locks taken with two int keys
   * apart from those taken with one bigint key, so these never meet {@link DatabaseLocks}' own.
   */
  static final int LOCK_SPACE = 0x4a44_4242;

  private final BlobStore blobs;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public BlobReferences(
      BlobStore blobs, JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.blobs = blobs;
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** Content staged for a reference, and whether it has been put in place as its blob. */
  public static final class Prepared {

    private final Staged staged;
    private final long ownerId;
    private boolean placed;

    private Prepared(Staged staged, long ownerId, boolean placed) {
      this.staged = staged;
      this.ownerId = ownerId;
      this.placed = placed;
    }

    /** The content's SHA-256, as 64 lowercase hex digits: the blob a row will point at. */
    public String hash() {
      return staged.hash();
    }

    /** The content's size in bytes. */
    public long size() {
      return staged.size();
    }

    /** Whether it was put in place as its blob, rather than left staged. */
    boolean placed() {
      return placed;
    }
  }

  /**
   * Stages {@code content} for a reference that {@code ownerId}'s account is about to take: see the
   * steps above. Must run outside a transaction, since putting a large blob in place takes as long
   * as copying it, and no connection should be held meanwhile.
   *
   * @param length how many bytes {@code content} holds, or -1 if that is not known
   */
  public Prepared prepare(InputStream content, long length, long ownerId) throws IOException {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Content is put in place outside a transaction");
    }
    Staged staged = blobs.stage(content, length);
    try {
      claim(staged);
      // Only a hint without the lock; add() asks again under it.
      boolean holds = holds(staged.hash(), ownerId);
      if (!holds) {
        blobs.placeAnyway(staged);
      }
      return new Prepared(staged, ownerId, !holds);
    } catch (IOException | RuntimeException e) {
      blobs.discard(staged);
      throw e;
    }
  }

  /**
   * Keeps the blob from being deleted for the grace period, before its content is put in place: in
   * a short transaction of its own, under the blob's lock, gives it a row if it has none, and marks
   * one nothing points at as released just now. {@link BlobCollector} deletes only a blob released
   * longer ago, under the same lock, so a delete either finished before this, and this makes the
   * row again, or doesn't happen until the grace period has passed. That covers the upload putting
   * its content in place, which takes no lock, and {@link #add} after it. A blob placed for an
   * upload that then fails is in the job's queue all the same.
   */
  private void claim(Staged staged) {
    transactions.executeWithoutResult(
        status -> {
          lock(Set.of(staged.hash()));
          jdbc.update(
              "INSERT INTO blobs (hash, size, reference_count, created_at, released_at)"
                  + " VALUES (?, ?, 0, now(), now()) ON CONFLICT (hash) DO UPDATE SET"
                  + " released_at = CASE WHEN blobs.reference_count = 0 THEN now() END",
              staged.hash(),
              staged.size());
        });
  }

  /**
   * Takes the locks of these blobs, held until the transaction ends. Call it once in a transaction,
   * with every blob it will add or remove a reference to, before touching any row: each transaction
   * then takes its locks in one order, by their keys, and two can't each wait for a lock the other
   * holds. A replace with the same content removes and adds a reference to one blob in one
   * transaction, so the same hash may be given twice.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lock(Collection<String> hashes) {
    if (TransactionSynchronizationManager.hasResource(this)) {
      throw new IllegalStateException("A transaction takes all its blob locks at once");
    }
    Set<String> held = Set.copyOf(hashes);
    TransactionSynchronizationManager.bindResource(this, held);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(BlobReferences.this);
          }
        });
    // By the lock's key, not the hash: two hashes can share a key, and an order the keys don't
    // follow could still deadlock.
    Set<Integer> keys = new TreeSet<>();
    for (String hash : held) {
      keys.add(lockKey(hash));
    }
    for (int key : keys) {
      jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", row -> null, LOCK_SPACE, key);
    }
  }

  /**
   * Counts a reference to the prepared content's blob, for a file or version about to point at it
   * in this transaction. Under {@link #lock}. Checks again whether the account holds the content:
   * if it doesn't after all, the blob is put in place with the same copy as in {@link #prepare}; if
   * it does but the blob has gone meanwhile, it is put back.
   *
   * @return the blob's hash
   */
  @Transactional(propagation = Propagation.MANDATORY, rollbackFor = IOException.class)
  public String add(Prepared prepared) throws IOException {
    String hash = prepared.hash();
    requireLocked(hash);
    if (!prepared.placed) {
      if (!holds(hash, prepared.ownerId)) {
        blobs.placeAnyway(prepared.staged);
        prepared.placed = true;
      } else if (blobs.stat(hash).isEmpty()) {
        blobs.place(prepared.staged);
        prepared.placed = true;
      }
    } else if (blobs.stat(hash).isEmpty()) {
      // Put in place in prepare(), then deleted before this took the lock: only possible when
      // more than the grace period passed between the two, and nothing is left to put back.
      throw new IOException("Blob " + hash + " went before it was referenced; try again");
    }
    // Normally made by prepare(); again here should its row have been deleted with its blob.
    jdbc.update(
        "INSERT INTO blobs (hash, size, reference_count, created_at, released_at)"
            + " VALUES (?, ?, 0, now(), now()) ON CONFLICT (hash) DO NOTHING",
        hash,
        prepared.size());
    int counted =
        jdbc.update(
            "UPDATE blobs SET reference_count = reference_count + 1, released_at = NULL"
                + " WHERE hash = ? AND size = ?",
            hash,
            prepared.size());
    if (counted != 1) {
      throw new IllegalStateException("Blob " + hash + " is recorded with another size");
    }
    return hash;
  }

  /**
   * Takes a reference off a blob, for a file or version that stops pointing at it in this
   * transaction. Under {@link #lock}. A blob whose count falls to zero is released, and deleted
   * once the grace period has passed with nothing pointing at it again.
   *
   * @throws IllegalStateException if the blob has no reference to take off, which is a bug
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void remove(String hash) {
    requireLocked(hash);
    int counted =
        jdbc.update(
            "UPDATE blobs SET reference_count = reference_count - 1, released_at ="
                + " CASE WHEN reference_count = 1 THEN now() ELSE NULL END"
                + " WHERE hash = ? AND reference_count > 0",
            hash);
    if (counted != 1) {
      throw new IllegalStateException("Took a reference off blob " + hash + ", which has none");
    }
  }

  /** Drops what {@link #prepare} staged and didn't put in place. Never throws. */
  public void discard(Prepared prepared) {
    if (!prepared.placed) {
      blobs.discard(prepared.staged);
    }
  }

  /**
   * Whether the account has a file or a version that points at the blob: one index lookup each, on
   * {@code (blob_hash, user_id)} and {@code (blob_hash, owner_id)}.
   */
  boolean holds(String hash, long ownerId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM file_metadata WHERE blob_hash = ? AND user_id = ?)"
                + " OR EXISTS (SELECT 1 FROM file_versions WHERE blob_hash = ? AND owner_id = ?)",
            Boolean.class,
            hash,
            ownerId,
            hash,
            ownerId));
  }

  private void requireLocked(String hash) {
    Object held = TransactionSynchronizationManager.getResource(this);
    if (!(held instanceof Set<?> hashes) || !hashes.contains(hash)) {
      throw new IllegalStateException("Blob " + hash + " is changed without its lock");
    }
  }

  /** The advisory lock of a blob: its hash's first 32 bits. */
  static int lockKey(String hash) {
    BlobStore.key(hash);
    return HexFormat.fromHexDigits(hash, 0, 8);
  }
}
