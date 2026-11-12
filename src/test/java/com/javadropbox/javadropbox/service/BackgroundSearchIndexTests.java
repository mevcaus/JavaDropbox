package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.service.StoragePaths.Home;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * How every search index keeps up with the store, on a backend that only records the batches it is
 * given. Each backend inherits this, so it is tested here without one.
 */
@DisplayName("Keeping a search index up with the store")
@Timeout(30)
class BackgroundSearchIndexTests {

  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final Duration RETRY = Duration.ofMillis(200);
  private static final Duration MAX_RETRY = Duration.ofMillis(800);

  @TempDir Path servingDir;

  private Home home;
  private final Recorder index = new Recorder();

  @BeforeEach
  void setUp() throws IOException {
    home = new StoragePaths(servingDir).home(1);
  }

  @AfterEach
  void tearDown() throws Exception {
    index.close();
  }

  @Test
  @DisplayName("starting reconciles the whole index")
  void startReconciles() throws Exception {
    index.start();
    index.awaitIdle(WAIT);

    assertThat(index.batches).containsExactly(new Batch(true, List.of()));
  }

  @Test
  @DisplayName("changes reported while a batch is written go together into the next one")
  void changesDuringABatchGoIntoTheNext() throws Exception {
    index.block();
    index.changed(home, "a");
    assertThat(index.writing.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

    index.changed(home, "b");
    index.changed(home, "c");
    index.changed(home, "b");
    index.release();
    index.awaitIdle(WAIT);

    assertThat(index.batches)
        .containsExactly(new Batch(false, List.of("1/a")), new Batch(false, List.of("1/b", "1/c")));
  }

  @Test
  @DisplayName(
      "a batch the index can't take yet is tried again later, with what was reported since")
  void retriedBatchesKeepTheirChanges() throws Exception {
    index.outcomes.add(BackgroundSearchIndex.Outcome.RETRY);
    index.block();
    index.reconcile();
    assertThat(index.writing.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
    index.changed(home, "a");
    index.release();
    index.awaitIdle(WAIT);

    assertThat(index.batches)
        .containsExactly(new Batch(true, List.of()), new Batch(true, List.of("1/a")));
    assertThat(Duration.between(index.started.get(0), index.started.get(1)))
        .isGreaterThanOrEqualTo(RETRY);
  }

  @Test
  @DisplayName("retries in a row wait twice as long each time, up to the most they may")
  void retriesBackOff() throws Exception {
    for (int i = 0; i < 4; i++) {
      index.outcomes.add(BackgroundSearchIndex.Outcome.RETRY);
    }
    index.changed(home, "a");
    index.awaitIdle(WAIT);

    List<Duration> waits = waits();
    assertThat(waits).hasSize(4);
    assertThat(waits.get(0)).isGreaterThanOrEqualTo(RETRY);
    assertThat(waits.get(1)).isGreaterThanOrEqualTo(RETRY.multipliedBy(2));
    assertThat(waits.get(2)).isGreaterThanOrEqualTo(MAX_RETRY);
    // Not doubled again: 1600 ms.
    assertThat(waits.get(3))
        .isGreaterThanOrEqualTo(MAX_RETRY)
        .isLessThan(MAX_RETRY.multipliedBy(2));
  }

  @Test
  @DisplayName("once a batch gets through, the next retry waits only the first delay again")
  void retriesStartOverOnceThrough() throws Exception {
    for (int i = 0; i < 3; i++) {
      index.outcomes.add(BackgroundSearchIndex.Outcome.RETRY);
    }
    index.changed(home, "a");
    index.awaitIdle(WAIT);
    index.outcomes.add(BackgroundSearchIndex.Outcome.RETRY);
    index.changed(home, "b");
    index.awaitIdle(WAIT);

    List<Duration> waits = waits();
    assertThat(waits).hasSize(5);
    // b's retry, after a's three: 200 ms again, not 800.
    assertThat(waits.get(4)).isGreaterThanOrEqualTo(RETRY).isLessThan(MAX_RETRY);
  }

  private List<Duration> waits() {
    List<Duration> waits = new ArrayList<>();
    for (int i = 1; i < index.started.size(); i++) {
      waits.add(Duration.between(index.started.get(i - 1), index.started.get(i)));
    }
    return waits;
  }

  @Test
  @DisplayName("a batch that fails is given up, and the next one still runs")
  void failedBatchIsGivenUp() throws Exception {
    index.fail.set(true);
    index.changed(home, "a");
    index.awaitIdle(WAIT);
    index.changed(home, "b");
    index.awaitIdle(WAIT);

    assertThat(index.batches)
        .containsExactly(new Batch(false, List.of("1/a")), new Batch(false, List.of("1/b")));
  }

  @Test
  @DisplayName("closing tells a batch under way, and releases the index only once it has ended")
  void closingStopsABatch() throws Exception {
    index.block();
    index.changed(home, "a");
    assertThat(index.writing.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

    Thread closer = new Thread(this::closeQuietly);
    closer.start();
    // The batch sees the app closing while it is still under way, and the index isn't released
    // under it.
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!index.closing()) {
      assertThat(System.nanoTime()).isLessThan(deadline);
      Thread.onSpinWait();
    }
    Thread.sleep(100);
    assertThat(index.closed).isFalse();
    index.release();
    closer.join(WAIT.toMillis());

    assertThat(index.closed).isTrue();
    // Nothing reported after closing is written.
    index.changed(home, "late");
    assertThat(index.batches).containsExactly(new Batch(false, List.of("1/a")));
  }

  @Test
  @DisplayName("waiting for a batch that doesn't end gives up after the time it was given")
  void awaitIdleGivesUp() throws Exception {
    index.block();
    index.changed(home, "a");
    assertThat(index.writing.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

    assertThatThrownBy(() -> index.awaitIdle(Duration.ofMillis(100)))
        .isInstanceOf(IllegalStateException.class);
    index.release();
  }

  private void closeQuietly() {
    try {
      index.close();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private record Batch(boolean reconcileAll, List<String> changed) {}

  /** Records each batch; can hold one, fail it, or have it tried again. */
  private static final class Recorder extends BackgroundSearchIndex {

    final List<Batch> batches = new CopyOnWriteArrayList<>();
    final List<Instant> started = new CopyOnWriteArrayList<>();
    final Queue<Outcome> outcomes = new ArrayDeque<>();
    final AtomicBoolean fail = new AtomicBoolean();
    volatile CountDownLatch writing = new CountDownLatch(0);
    private volatile CountDownLatch go = new CountDownLatch(0);
    volatile boolean closed;

    Recorder() {
      super(RETRY, MAX_RETRY);
    }

    void block() {
      writing = new CountDownLatch(1);
      go = new CountDownLatch(1);
    }

    void release() {
      go.countDown();
    }

    @Override
    Outcome write(boolean reconcileAll, List<String> changed) throws IOException {
      batches.add(new Batch(reconcileAll, changed));
      started.add(Instant.now());
      writing.countDown();
      try {
        if (!go.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
          throw new IllegalStateException("never let go");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      go = new CountDownLatch(0);
      if (fail.getAndSet(false)) {
        throw new IOException("failed");
      }
      Outcome outcome = outcomes.poll();
      return outcome == null ? Outcome.DONE : outcome;
    }

    @Override
    void closeIndex() {
      closed = true;
    }

    @Override
    public Hits search(Home home, String folderKey, String text, int limit) {
      return new Hits(List.of(), 0, isCaughtUp());
    }
  }
}
