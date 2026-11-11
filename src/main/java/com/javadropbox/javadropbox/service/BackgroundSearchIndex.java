package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.service.StoragePaths.Home;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * How every {@link SearchIndex} keeps up with the store, whatever it is built on. Changes are
 * written on one background thread, in batches: everything reported since the last batch, so an
 * upload never waits for a PDF to be read. A change reported inside a transaction is queued once
 * the transaction has finished. The whole index is reconciled at startup and every ten minutes. An
 * implementation writes a batch ({@link #write}) and searches; what is written comes from {@link
 * IndexableItems}.
 */
abstract class BackgroundSearchIndex implements SearchIndex {

  static final Duration RECONCILE_INTERVAL = Duration.ofMinutes(10);
  // Before trying a batch again that the index could not take yet, such as while a second server
  // on the same storage holds its lock.
  static final Duration RETRY_DELAY = Duration.ofSeconds(5);
  private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(10);

  private static final Logger log = LoggerFactory.getLogger(BackgroundSearchIndex.class);

  /** Whether a batch is done, or has to be tried again after {@link #RETRY_DELAY}. */
  enum Outcome {
    DONE,
    RETRY
  }

  protected final IndexableItems items;
  private final ScheduledThreadPoolExecutor worker;

  // What the worker has yet to do, and whether it is at it. Guarded by lock.
  private final Object lock = new Object();
  private boolean reconcilePending;
  private final Set<String> changedPending = new LinkedHashSet<>();
  private boolean busy;

  private volatile boolean caughtUp;
  private volatile boolean closing;

  BackgroundSearchIndex(IndexableItems items) {
    this.items = items;
    this.worker =
        new ScheduledThreadPoolExecutor(
            1,
            task -> {
              Thread thread = new Thread(task, "search-index");
              thread.setDaemon(true);
              return thread;
            });
    worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  /**
   * Writes one batch: everything there is when {@code reconcileAll}, as {@link
   * IndexableItems#reconcile} reports it differs, and then each changed key, as {@link
   * IndexableItems#walkUnder} finds it. Called only on the worker thread. An exception gives the
   * batch up, logged: what it did not get to, the next reconcile will.
   */
  protected abstract Outcome write(boolean reconcileAll, List<String> changed) throws IOException;

  /** Releases what the index holds, once the worker has stopped. */
  protected abstract void closeIndex() throws IOException;

  /** Whether the app is shutting down: a batch should stop where it is. */
  protected final boolean closing() {
    return closing;
  }

  /** Records that the index has caught up with the store since the server started. */
  protected final void caughtUp() {
    caughtUp = true;
  }

  /** Whether the index has caught up with the store since the server started. */
  protected final boolean isCaughtUp() {
    return caughtUp;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    reconcile();
    long interval = RECONCILE_INTERVAL.toMillis();
    worker.scheduleWithFixedDelay(this::reconcile, interval, interval, TimeUnit.MILLISECONDS);
  }

  @Override
  public void changed(Home home, String key) {
    changed(home.indexKey(key));
  }

  @Override
  public void changedEverywhere() {
    changed("");
  }

  // The empty key is every account's folder.
  private void changed(String key) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              enqueue(key);
            }
          });
    } else {
      enqueue(key);
    }
  }

  @Override
  public void reconcile() {
    synchronized (lock) {
      reconcilePending = true;
      schedule();
    }
  }

  private void enqueue(String key) {
    synchronized (lock) {
      changedPending.add(key);
      schedule();
    }
  }

  // Called holding lock.
  private void schedule() {
    if (!busy && !closing) {
      busy = true;
      worker.execute(this::drain);
    }
  }

  @Override
  public void awaitIdle(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    synchronized (lock) {
      while (busy) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          throw new IllegalStateException("Search indexing did not finish within " + timeout);
        }
        TimeUnit.NANOSECONDS.timedWait(lock, left);
      }
    }
  }

  // One batch: everything reported since the last one.
  private void drain() {
    boolean reconcileAll;
    List<String> changed;
    synchronized (lock) {
      reconcileAll = reconcilePending;
      changed = List.copyOf(changedPending);
      reconcilePending = false;
      changedPending.clear();
    }

    boolean retry = false;
    try {
      retry = write(reconcileAll, changed) == Outcome.RETRY;
    } catch (IOException | RuntimeException e) {
      // What this batch did not get to, the next reconcile will.
      log.warn("Could not update the search index", e);
    } finally {
      synchronized (lock) {
        if (retry) {
          reconcilePending |= reconcileAll;
          changedPending.addAll(changed);
        }
        if (closing || (!retry && !reconcilePending && changedPending.isEmpty())) {
          busy = false;
          lock.notifyAll();
        } else if (retry) {
          worker.schedule(this::drain, RETRY_DELAY.toMillis(), TimeUnit.MILLISECONDS);
        } else {
          worker.execute(this::drain);
        }
      }
    }
  }

  @PreDestroy
  public void close() throws IOException, InterruptedException {
    synchronized (lock) {
      closing = true;
    }
    worker.shutdown();
    if (!worker.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
      log.warn("Search indexing did not stop within {}", SHUTDOWN_GRACE);
    }
    closeIndex();
  }
}
