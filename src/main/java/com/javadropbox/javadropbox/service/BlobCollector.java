package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.service.FileStore.Entry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes blobs nothing has pointed at for {@code javadropbox.blobs.release-grace} (#316): their
 * rows, then what the store holds. Runs in the background on every server, at startup and then
 * every quarter of the grace period, so a request never waits for it and a crash after a count fell
 * to zero is caught up with later. A round goes on while there's more to delete.
 *
 * <p>Each blob is deleted in a transaction of its own, under its lock (see {@link
 * BlobReferences#lock}), after checking again that its count is zero and that it was released
 * longer ago than the grace period. An upload claims a blob under the same lock before it puts its
 * content in place, which marks it released just now, so the job never deletes a blob an upload is
 * about to point at. A blob another server is working on is left for a later round. Times are the
 * database's, the same clock that recorded the release.
 *
 * <p>Before deleting, the job also asks whether any file or version points at the blob after all.
 * If one does, its count was wrong: it is set to what points at it, and the blob kept.
 */
@Component
public class BlobCollector {

  private static final Logger log = LoggerFactory.getLogger(BlobCollector.class);

  // At most this many per batch, so one transaction-free listing stays small.
  private static final int BATCH = 1000;

  private final BlobReferences references;
  private final BlobStore blobs;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Duration grace;
  private final boolean enabled;
  private final ScheduledThreadPoolExecutor worker;
  // Run once a batch is listed, and with a blob's lock held once it is found due, before it is
  // deleted: for tests that step in at those points.
  private Runnable afterListed = () -> {};
  private Runnable afterDecided = () -> {};

  public BlobCollector(
      BlobReferences references,
      BlobStore blobs,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      @Value("${javadropbox.blobs.release-grace:1h}") Duration grace,
      @Value("${javadropbox.blobs.collect:true}") boolean enabled) {
    // Checked here so a bad setting stops the app starting. Shorter than a minute would also
    // have to outlast every large upload's copy into place, and would run the job all the time.
    if (grace.compareTo(Duration.ofMinutes(1)) < 0) {
      throw new IllegalArgumentException(
          "javadropbox.blobs.release-grace must be at least 1m, not " + grace);
    }
    this.references = references;
    this.blobs = blobs;
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
    this.grace = grace;
    this.enabled = enabled;
    this.worker =
        new ScheduledThreadPoolExecutor(
            1,
            task -> {
              Thread thread = new Thread(task, "blob-collector");
              thread.setDaemon(true);
              return thread;
            });
    worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    if (!enabled) {
      return;
    }
    long every = grace.toMillis() / 4;
    worker.scheduleWithFixedDelay(this::collectLogged, 0, every, TimeUnit.MILLISECONDS);
  }

  void afterListed(Runnable hook) {
    this.afterListed = hook;
  }

  void afterDecided(Runnable hook) {
    this.afterDecided = hook;
  }

  @PreDestroy
  public void stop() {
    // A delete under way finishes or rolls back with its transaction; the next start carries on.
    worker.shutdownNow();
  }

  // Anything thrown out of a scheduled task would end the schedule for good, silently.
  private void collectLogged() {
    try {
      collect();
    } catch (Throwable e) {
      log.error("Could not delete unused blobs; trying again next round", e);
    }
  }

  /**
   * Deletes the blobs released longer ago than the grace period, batch after batch while each is
   * full.
   *
   * @return how many were deleted
   */
  int collect() {
    int deleted = 0;
    for (int batch = 0; !Thread.currentThread().isInterrupted(); batch++) {
      List<String> due =
          jdbc.queryForList(
              "SELECT hash FROM blobs WHERE reference_count = 0"
                  + " AND released_at < now() - make_interval(secs => ?)"
                  + " ORDER BY released_at LIMIT ?",
              String.class,
              grace.toSeconds(),
              BATCH);
      afterListed.run();
      int skipped = 0;
      for (String hash : due) {
        if (Thread.currentThread().isInterrupted()) {
          break;
        }
        switch (deleteIfDue(hash)) {
          case DELETED -> deleted++;
          case SKIPPED -> skipped++;
          case KEPT -> {}
        }
      }
      // A batch of nothing but blobs others hold would only be listed again.
      if (due.size() < BATCH || skipped == due.size()) {
        break;
      }
      if (batch == 0) {
        log.warn("More than {} unused blobs to delete; deleting them in batches", BATCH);
      }
    }
    if (deleted > 0) {
      log.info("Deleted {} blobs nothing pointed at", deleted);
    }
    return deleted;
  }

  private enum Outcome {
    DELETED,
    KEPT,
    SKIPPED
  }

  private Outcome deleteIfDue(String hash) {
    try {
      return transactions.execute(status -> delete(hash));
    } catch (RuntimeException e) {
      // One blob's trouble doesn't stop the others; it is tried again next round.
      log.error("Could not delete blob {}", hash, e);
      return Outcome.KEPT;
    }
  }

  // Under the blob's lock: whether it is still due, and if so its row, then its content.
  private Outcome delete(String hash) {
    if (!references.tryLock(hash)) {
      // Another server is deleting it, or an upload is claiming it.
      return Outcome.SKIPPED;
    }
    Boolean due =
        jdbc.query(
            "SELECT reference_count = 0 AND released_at < now() - make_interval(secs => ?)"
                + " FROM blobs WHERE hash = ?",
            rows -> rows.next() ? rows.getBoolean(1) : null,
            grace.toSeconds(),
            hash);
    if (!Boolean.TRUE.equals(due)) {
      // Referenced again, claimed by an upload, or gone, since it was listed.
      return Outcome.KEPT;
    }
    int pointing =
        jdbc.queryForObject(
            "SELECT (SELECT count(*) FROM file_metadata WHERE blob_hash = ?)"
                + " + (SELECT count(*) FROM file_versions WHERE blob_hash = ?)",
            Integer.class,
            hash,
            hash);
    if (pointing > 0) {
      log.warn(
          "Blob {} had no references counted but {} rows point at it; recounted", hash, pointing);
      jdbc.update(
          "UPDATE blobs SET reference_count = ?, released_at = NULL WHERE hash = ?",
          pointing,
          hash);
      return Outcome.KEPT;
    }
    afterDecided.run();
    try {
      Optional<Entry> content = blobs.stat(hash);
      jdbc.update("DELETE FROM blobs WHERE hash = ?", hash);
      if (content.isPresent()) {
        blobs.delete(hash);
      }
      return Outcome.DELETED;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
