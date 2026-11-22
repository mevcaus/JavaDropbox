package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.service.FileStore.Entry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * to zero is caught up with later.
 *
 * <p>Each blob is deleted in a transaction of its own, under its lock (see {@link
 * BlobReferences#lock}), after checking again that nothing points at it and that it was released
 * longer ago than the grace period. An upload claims a blob under the same lock before it puts its
 * content in place, which marks it released just now, so the job never deletes a blob an upload is
 * about to point at. The row goes before the content, so a reference the count missed, which the
 * foreign keys catch, stops the delete before anything is lost.
 */
@Component
public class BlobCollector {

  private static final Logger log = LoggerFactory.getLogger(BlobCollector.class);

  // At most this many per round, so one round doesn't run for ever after a large delete.
  private static final int BATCH = 1000;

  private final BlobReferences references;
  private final BlobStore blobs;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Duration grace;
  private final Clock clock;
  private final ScheduledThreadPoolExecutor worker;
  // Run with the lock held, once a blob is found due and before it is deleted: for tests that
  // hold a delete there while something else tries to reach the blob.
  private Runnable afterDecided = () -> {};

  @Autowired
  public BlobCollector(
      BlobReferences references,
      BlobStore blobs,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      @Value("${javadropbox.blobs.release-grace:1h}") Duration grace) {
    this(references, blobs, jdbc, transactionManager, grace, Clock.systemUTC());
  }

  BlobCollector(
      BlobReferences references,
      BlobStore blobs,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      Duration grace,
      Clock clock) {
    // Checked here so a bad setting stops the app starting.
    if (grace.isNegative() || grace.isZero()) {
      throw new IllegalArgumentException(
          "javadropbox.blobs.release-grace must be more than 0, not " + grace);
    }
    this.references = references;
    this.blobs = blobs;
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
    this.grace = grace;
    this.clock = clock;
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
    long every = Math.max(1, grace.toMillis() / 4);
    worker.scheduleWithFixedDelay(this::collectLogged, 0, every, TimeUnit.MILLISECONDS);
  }

  void afterDecided(Runnable hook) {
    this.afterDecided = hook;
  }

  @PreDestroy
  public void stop() {
    // A delete under way finishes or rolls back with its transaction; the next start carries on.
    worker.shutdownNow();
  }

  private void collectLogged() {
    try {
      collect();
    } catch (RuntimeException e) {
      // Tried again next round.
      log.warn("Could not delete unused blobs: {}", e.toString());
    }
  }

  /**
   * Deletes the blobs released longer ago than the grace period, as many as one round takes.
   *
   * @return how many were deleted
   */
  int collect() {
    Instant before = clock.instant().minus(grace);
    List<String> due =
        jdbc.queryForList(
            "SELECT hash FROM blobs WHERE reference_count = 0 AND released_at < ?"
                + " ORDER BY released_at LIMIT ?",
            String.class,
            Timestamp.from(before),
            BATCH);
    int deleted = 0;
    for (String hash : due) {
      try {
        if (Boolean.TRUE.equals(transactions.execute(status -> delete(hash, before)))) {
          deleted++;
        }
      } catch (RuntimeException e) {
        // One blob's trouble, such as a reference its count missed, doesn't stop the others.
        log.error("Could not delete blob {}", hash, e);
      }
    }
    if (deleted > 0) {
      log.info("Deleted {} blobs nothing pointed at", deleted);
    }
    return deleted;
  }

  // Under the blob's lock: whether it is still due, and if so its row, then its content.
  private boolean delete(String hash, Instant before) {
    references.lock(List.of(hash));
    Boolean due =
        jdbc.query(
            "SELECT reference_count = 0 AND released_at < ? FROM blobs WHERE hash = ?",
            rows -> rows.next() ? rows.getBoolean(1) : null,
            Timestamp.from(before),
            hash);
    if (!Boolean.TRUE.equals(due)) {
      // Referenced again, claimed by an upload, or gone, since it was listed.
      return false;
    }
    afterDecided.run();
    try {
      Optional<Entry> content = blobs.stat(hash);
      jdbc.update("DELETE FROM blobs WHERE hash = ?", hash);
      if (content.isPresent()) {
        blobs.delete(hash);
      }
      return true;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
