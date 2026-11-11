package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.exception.InsufficientStorageException;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;

/**
 * What each account stores, previous versions included, and its quota. The total is kept in {@code
 * users.used_bytes}: every change to an account's files or versions adds or takes away its bytes as
 * it goes ({@link #add}), and the transaction's net change is written once, right before it
 * commits, so the account's row is locked for as short a time as possible.
 *
 * <p>A change that has to fit the quota ({@link #mustFit}), an upload or a restore, writes its gain
 * with one statement that charges it and checks the quota together. Of two such changes at once,
 * the second waits for the first's row lock and sees what it charged, so they can't both fit where
 * only one does. A change that doesn't fit throws {@link InsufficientStorageException} before
 * commit, and the transaction rolls back with everything it did.
 */
@Component
public class StorageQuota {

  /** The setting the server-wide cap was read from. */
  static final String REMOVED_CAP = "javadropbox.storage.max-total-size";

  /** What an account stores, previous versions included, and its quota (null for none). */
  @NullMarked
  public record Usage(long usedBytes, @Nullable Long quotaBytes) {}

  private static final Logger log = LoggerFactory.getLogger(StorageQuota.class);

  private final UserRepository users;

  public StorageQuota(UserRepository users, @Value("${" + REMOVED_CAP + ":}") String removedCap) {
    if (!removedCap.isBlank()) {
      throw new IllegalStateException(
          REMOVED_CAP
              + " is no longer supported: give each account a quota in the app instead (Users,"
              + " see docs/self-hosting.md), then remove the setting.");
    }
    this.users = users;
  }

  /** What {@code user} stores now, read from the database, and their quota. */
  public Usage usage(User user) {
    return new Usage(users.findUsedBytes(user.getId()), user.getQuotaBytes());
  }

  /**
   * Refuses an upload or restore of {@code incoming} bytes that can't fit, before it touches the
   * store, so that the common case never has to be undone. Only a first look: the charge at commit
   * decides, since another change may come in between.
   *
   * @throws InsufficientStorageException if what the account stores and {@code incoming} together
   *     are over its quota
   */
  void refuseIfOver(User user, long incoming) {
    Long limit = user.getQuotaBytes();
    if (limit != null && users.findUsedBytes(user.getId()) + incoming > limit) {
      throw overQuota(user.getId());
    }
  }

  /**
   * Counts {@code bytes} more (or, negative, fewer) against the account with id {@code ownerId}
   * once the current transaction commits. Called where a file's or a version's row is added,
   * resized or removed, in that transaction.
   */
  void add(long ownerId, long bytes) {
    if (bytes != 0) {
      ledger().deltas.merge(ownerId, bytes, Long::sum);
    }
  }

  /** Makes what the current transaction adds to the account's total have to fit its quota. */
  void mustFit(long ownerId) {
    ledger().mustFit.add(ownerId);
  }

  private Ledger ledger() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("No transaction to count storage in");
    }
    // Kept in the transaction's own synchronizations, as OnRollback keeps its undos, so a
    // transaction started inside it has a ledger of its own.
    for (TransactionSynchronization registered :
        TransactionSynchronizationManager.getSynchronizations()) {
      if (registered instanceof Ledger ledger) {
        return ledger;
      }
    }
    Ledger ledger = new Ledger();
    TransactionSynchronizationManager.registerSynchronization(ledger);
    return ledger;
  }

  private final class Ledger implements TransactionSynchronization {

    // By id, so that two transactions changing the same accounts lock them in the same order.
    private final Map<Long, Long> deltas = new TreeMap<>();
    private final Set<Long> mustFit = new HashSet<>();

    @Override
    public void beforeCommit(boolean readOnly) {
      deltas.forEach(
          (id, delta) -> {
            if (delta > 0 && mustFit.contains(id)) {
              if (users.chargeWithinQuota(id, delta) == 0) {
                throw overQuota(id);
              }
            } else {
              if (delta < 0 && users.findUsedBytes(id) + delta < 0) {
                // Something counted fewer bytes than it took off: kept at zero, and set right by
                // the recount at the next start.
                log.warn(
                    "Account {} would store less than nothing ({} bytes off); counted as 0",
                    id,
                    -delta);
              }
              users.adjustUsedBytes(id, delta);
            }
          });
    }
  }

  private InsufficientStorageException overQuota(long id) {
    Long quota = users.findById(id).map(User::getQuotaBytes).orElse(null);
    return new InsufficientStorageException(
        "Not enough storage space: your account can hold at most "
            + (quota == null ? "what it holds" : describe(DataSize.ofBytes(quota)))
            + " of files, previous versions included. Delete something to make room.");
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
