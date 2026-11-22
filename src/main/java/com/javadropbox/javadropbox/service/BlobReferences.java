package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.service.BlobStore.Staged;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
 * <p>How new content becomes a reference:
 *
 * <ol>
 *   <li>{@link #prepare}, outside any transaction, stages the content and hashes it. Unless the
 *       uploading account already holds that content, it puts it in place as its blob, with the
 *       same copy whether or not another account holds it, so how long an upload takes says nothing
 *       about anyone else's files (decided on #316, as for sync in #202). Before that, it claims
 *       the blob under its lock for a moment, so it isn't deleted meanwhile (see {@link #claim}).
 *   <li>In the transaction, {@link #lock} takes the locks of every blob it will add or remove a
 *       reference to, before touching any other row.
 *   <li>{@link #add(Prepared)} checks, under the lock, that the blob is there, puts it back if it
 *       went meanwhile, and counts the reference. {@link #add(String)} counts another reference to
 *       a blob already stored, as a restore or an archived version takes, and {@link #remove} takes
 *       one off. {@link #removeAll} takes many off at once, as deleting a folder does.
 *   <li>Afterwards, {@link #discard} drops what was staged and not used.
 * </ol>
 *
 * <p>A blob whose count falls to zero is deleted by {@link BlobCollector}, under the same lock,
 * once it has stayed at zero for the grace period. Whoever is about to put a blob in place claims
 * it under that lock first, and whoever deletes one takes it too, so a blob can't be deleted under
 * a new reference. Taking a reference off needs no advisory lock: it can't make a blob be deleted
 * any sooner than the grace period after.
 *
 * <p>Locks on {@code blobs} rows are always taken in the order of their hashes, all at once, by
 * {@link #lock} and {@link #removeAll}, so two transactions never wait on each other's rows.
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
    private boolean placed;

    private Prepared(Staged staged, boolean placed) {
      this.staged = staged;
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
      // Whether to copy is decided here, under the lock, from the account's own rows, and not
      // again: the copy is what keeps the upload's timing the same whoever else holds the content.
      boolean holds = claim(staged, ownerId);
      if (!holds) {
        blobs.placeAnyway(staged);
      }
      return new Prepared(staged, !holds);
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
   *
   * <p>The time recorded is the statement's, once the lock is held, not the transaction's start, so
   * a claim that waited for the lock isn't dated before it happened.
   *
   * @return whether {@code ownerId}'s account already holds the content, asked under the lock
   */
  private boolean claim(Staged staged, long ownerId) {
    return Boolean.TRUE.equals(
        transactions.execute(
            status -> {
              lock(Set.of(staged.hash()));
              jdbc.update(
                  "INSERT INTO blobs (hash, size, reference_count, created_at, released_at)"
                      + " VALUES (?, ?, 0, now(), statement_timestamp()) ON CONFLICT (hash)"
                      + " DO UPDATE SET released_at ="
                      + " CASE WHEN blobs.reference_count = 0 THEN statement_timestamp() END",
                  staged.hash(),
                  staged.size());
              return holds(staged.hash(), ownerId);
            }));
  }

  /**
   * Takes the locks of these blobs, held until the transaction ends: their advisory locks, ordered
   * by key, then their rows, ordered by hash. Call it once in a transaction, with every blob it
   * will add a reference to or remove one from, before touching any other row: each transaction
   * then takes its locks in one order, and two can't each wait for a lock the other holds. A
   * replace with the same content removes a reference to a blob and adds one in one transaction, so
   * the same hash may be given twice.
   *
   * @throws IllegalStateException if the transaction already took its blob locks
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lock(Collection<String> hashes) {
    Set<String> wanted = Set.copyOf(hashes);
    // By the lock's key, not the hash: two hashes can share a key, and an order the keys don't
    // follow could still deadlock.
    Set<Integer> keys = new TreeSet<>();
    for (String hash : wanted) {
      keys.add(lockKey(hash));
    }
    HeldLocks.start(wanted);
    for (int key : keys) {
      jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", row -> null, LOCK_SPACE, key);
    }
    lockRows(wanted);
  }

  /**
   * Takes the lock of one blob if no one else holds its advisory lock, as {@link #lock} would, and
   * returns false otherwise, without waiting for it. Its row may still be waited for, behind a
   * {@link #removeAll} taking references off. For work that can skip a blob and come back to it.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  boolean tryLock(String hash) {
    HeldLocks.refuseIfHeld();
    boolean got =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?, ?)",
                Boolean.class,
                LOCK_SPACE,
                lockKey(hash)));
    if (!got) {
      return false;
    }
    HeldLocks.start(Set.of(hash));
    lockRows(Set.of(hash));
    return true;
  }

  // In the order of their hashes, as removeAll takes them too.
  private void lockRows(Collection<String> hashes) {
    jdbc.query(
        "SELECT hash FROM blobs WHERE hash = ANY (?) ORDER BY hash FOR UPDATE",
        row -> null,
        (Object) hashes.toArray(String[]::new));
  }

  /**
   * Counts a reference to the prepared content's blob, for a file or version about to point at it
   * in this transaction. Under {@link #lock}. If the blob went meanwhile, it is put back from what
   * {@link #prepare} staged; if nothing was left to put it back from, this fails, and the
   * transaction rolls back.
   *
   * @return the blob's hash
   */
  @Transactional(propagation = Propagation.MANDATORY, rollbackFor = IOException.class)
  public String add(Prepared prepared) throws IOException {
    String hash = prepared.hash();
    requireLocked(hash);
    // Gone, or torn by a crash before blobs were synced as they were staged.
    if (blobs.stat(hash).filter(blob -> blob.size() == prepared.size()).isEmpty()) {
      if (prepared.placed) {
        // Put in place in prepare(), then deleted before this took the lock: only possible when
        // more than the grace period passed between the two, and nothing is left to put back.
        throw new IOException("Blob " + hash + " went before it was referenced; try again");
      }
      // Only a put back, never the copy prepare() decided against: the timing is settled.
      blobs.place(prepared.staged);
      prepared.placed = true;
    }
    // Normally made by prepare(); again here should its row have been deleted with its blob.
    jdbc.update(
        "INSERT INTO blobs (hash, size, reference_count, created_at, released_at)"
            + " VALUES (?, ?, 0, now(), statement_timestamp()) ON CONFLICT (hash) DO NOTHING",
        hash,
        prepared.size());
    count(hash, prepared.size());
    return hash;
  }

  /**
   * Counts another reference to a blob already stored, for a file or version about to point at it
   * in this transaction: a restore, a restored copy, or the live content kept as a version. Under
   * {@link #lock}. Nothing is copied.
   *
   * @throws IOException if the blob isn't there, in the store or in the table
   */
  @Transactional(propagation = Propagation.MANDATORY, rollbackFor = IOException.class)
  public void add(String hash) throws IOException {
    requireLocked(hash);
    Long size =
        jdbc.query(
            "SELECT size FROM blobs WHERE hash = ?",
            rows -> rows.next() ? rows.getLong(1) : null,
            hash);
    if (size == null || blobs.stat(hash).isEmpty()) {
      throw new IOException("Blob " + hash + " isn't stored");
    }
    count(hash, size);
  }

  private void count(String hash, long size) {
    int counted =
        jdbc.update(
            "UPDATE blobs SET reference_count = reference_count + 1, released_at = NULL"
                + " WHERE hash = ? AND size = ?",
            hash,
            size);
    if (counted != 1) {
      throw new IllegalStateException("Blob " + hash + " is recorded with another size");
    }
  }

  /**
   * Takes a reference off a blob, for a file or version that stops pointing at it in this
   * transaction. Under {@link #lock}, which the blob's add may need in the same transaction. A blob
   * whose count falls to zero is released, and deleted once the grace period has passed with
   * nothing pointing at it again.
   *
   * @throws IllegalStateException if the blob has no reference to take off, which is a bug
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void remove(String hash) {
    requireLocked(hash);
    // Its row is already locked, by lock().
    takeOff(Map.of(hash, 1));
  }

  /**
   * Takes references off many blobs at once, as deleting a folder does: {@code counts} says how
   * many from each. Takes no advisory lock for each blob, however many there are: it locks their
   * rows, in the order of their hashes, then changes their counts. A transaction calls it instead
   * of {@link #lock}, not as well, and before touching any other row (find the counts first, then
   * call this, then delete the files' rows), so its rows are taken in one order like everyone's.
   *
   * @throws IllegalArgumentException if a count is less than 1
   * @throws IllegalStateException if a blob has fewer references than are taken off, which is a
   *     bug, and nothing is changed; or if the transaction already took its blob locks
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void removeAll(Map<String, Integer> counts) {
    if (counts.values().stream().anyMatch(taken -> taken == null || taken < 1)) {
      throw new IllegalArgumentException("Each blob loses at least one reference: " + counts);
    }
    if (counts.isEmpty()) {
      return;
    }
    HeldLocks.start(Set.of());
    lockRows(counts.keySet());
    takeOff(counts);
  }

  // Under the rows' locks.
  private void takeOff(Map<String, Integer> counts) {
    Map<String, Integer> sorted = new TreeMap<>(counts);
    String[] hashes = sorted.keySet().toArray(String[]::new);
    Integer[] taken = sorted.values().toArray(Integer[]::new);
    List<String> short_ =
        jdbc.queryForList(
            "SELECT t.hash FROM unnest(?::varchar[], ?::int[]) AS t (hash, taken)"
                + " LEFT JOIN blobs b ON b.hash = t.hash"
                + " WHERE b.hash IS NULL OR b.reference_count < t.taken",
            String.class,
            hashes,
            taken);
    if (!short_.isEmpty()) {
      throw new IllegalStateException(
          "Took more references off blobs " + short_ + " than they have");
    }
    jdbc.update(
        "UPDATE blobs b SET reference_count = b.reference_count - t.taken, released_at ="
            + " CASE WHEN b.reference_count = t.taken THEN statement_timestamp() END"
            + " FROM unnest(?::varchar[], ?::int[]) AS t (hash, taken) WHERE b.hash = t.hash",
        hashes,
        taken);
  }

  /** Drops what {@link #prepare} staged, unless it became the blob. Never throws. */
  public void discard(Prepared prepared) {
    // Even when placed: a put back that found the blob already there leaves the staged file.
    blobs.discard(prepared.staged);
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
    if (HeldLocks.current().filter(held -> held.hashes.contains(hash)).isEmpty()) {
      throw new IllegalStateException("Blob " + hash + " is changed without its lock");
    }
  }

  /** The advisory lock of a blob: its hash's first 32 bits. */
  static int lockKey(String hash) {
    BlobStore.key(hash);
    return HexFormat.fromHexDigits(hash, 0, 8);
  }

  /**
   * The blob locks the current transaction holds. Kept as one of its synchronizations, which Spring
   * sets aside along with the transaction when a new one starts inside it, so an inner transaction
   * never takes the outer one's locks for its own.
   *
   * <p>Two ways to get stuck, which nothing here can see: an inner transaction (REQUIRES_NEW)
   * asking for a blob lock its outer one holds waits for its own thread for ever, since the outer
   * one's connection holds it; and a NESTED one rolled back to its savepoint lets go of the locks
   * taken after it while this still says they are held. Take blob locks in neither.
   */
  private static final class HeldLocks implements TransactionSynchronization {

    private final Set<String> hashes;

    private HeldLocks(Set<String> hashes) {
      this.hashes = hashes;
    }

    static Optional<HeldLocks> current() {
      if (!TransactionSynchronizationManager.isSynchronizationActive()) {
        return Optional.empty();
      }
      return TransactionSynchronizationManager.getSynchronizations().stream()
          .filter(HeldLocks.class::isInstance)
          .map(HeldLocks.class::cast)
          .findFirst();
    }

    static void refuseIfHeld() {
      if (current().isPresent()) {
        throw new IllegalStateException("A transaction takes all its blob locks at once");
      }
    }

    static void start(Set<String> hashes) {
      refuseIfHeld();
      TransactionSynchronizationManager.registerSynchronization(new HeldLocks(hashes));
    }
  }
}
