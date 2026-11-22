package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.TempServingDirectory;
import com.javadropbox.javadropbox.service.BlobReferences.Prepared;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Blob references on the disk, and how their locks keep a delete and a new reference apart. The
 * locks are PostgreSQL's, whatever the store, so they are tested once, here.
 */
@DisplayName("Blob references on the disk")
@TempServingDirectory
class LocalBlobReferencesTests extends BlobReferencesContractTests {

  private static final Duration WAIT = Duration.ofSeconds(10);

  @Test
  @DisplayName("an upload of content being deleted waits, then puts it back and counts it")
  void uploadWaitsForADeleteUnderWay() throws Exception {
    Prepared old = prepare("deleted while reused", ada);
    String hash = old.hash();
    jdbc.update("UPDATE blobs SET released_at = now() - interval '2 hours' WHERE hash = ?", hash);
    CountDownLatch decided = new CountDownLatch(1);
    CountDownLatch carryOn = new CountDownLatch(1);
    BlobCollector collector = collector();
    collector.afterDecided(
        () -> {
          decided.countDown();
          await(carryOn);
        });

    CompletableFuture<Integer> deleting = CompletableFuture.supplyAsync(collector::collect);
    await(decided);
    CompletableFuture<Prepared> uploading =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return prepare("deleted while reused", bob);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    // The upload's claim waits for the delete's lock.
    eventually(() -> waitingForLock(hash));
    assertThat(uploading).isNotDone();
    carryOn.countDown();

    assertThat(deleting.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isOne();
    Prepared again = uploading.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    add(again);
    assertThat(blobs.stat(hash)).isPresent();
    assertThat(count(hash)).isOne();
  }

  @Test
  @DisplayName("a transaction takes its blob locks in one order, however it lists them")
  void locksAreTakenInOneOrder() throws Exception {
    // Lock keys 0x11111111 and 0x77777777: the first sorts first.
    String low = "1".repeat(64);
    String high = "7".repeat(64);
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> first =
        CompletableFuture.runAsync(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      references.lock(List.of(low));
                      holding.countDown();
                      await(release);
                    }));
    await(holding);

    // Listed high first, but takes low first: so it waits on low holding nothing, and high is
    // still free for anyone else.
    CompletableFuture<Void> second =
        CompletableFuture.runAsync(
            () -> transactions.executeWithoutResult(status -> references.lock(List.of(high, low))));
    eventually(() -> waitingForLock(low));
    Boolean highIsFree =
        transactions.execute(
            status ->
                jdbc.queryForObject(
                    "SELECT pg_try_advisory_xact_lock(?, ?)",
                    Boolean.class,
                    BlobReferences.LOCK_SPACE,
                    BlobReferences.lockKey(high)));
    release.countDown();

    first.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    second.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(highIsFree).isTrue();
  }

  // Whether a session is waiting for the lock of this blob.
  private boolean waitingForLock(String hash) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory' AND NOT granted"
                + " AND classid = ?::oid AND objid = ?::bigint::oid AND objsubid = 2)",
            Boolean.class,
            BlobReferences.LOCK_SPACE,
            Integer.toUnsignedLong(BlobReferences.lockKey(hash))));
  }

  // Checks a state until it holds, for up to WAIT; the order of events comes from the latches.
  private static void eventually(BooleanSupplier condition) throws InterruptedException {
    Instant until = Instant.now().plus(WAIT);
    while (!condition.getAsBoolean()) {
      if (Instant.now().isAfter(until)) {
        throw new AssertionError("Not so within " + WAIT);
      }
      Thread.sleep(10);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
        throw new AssertionError("Not reached within " + WAIT);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
