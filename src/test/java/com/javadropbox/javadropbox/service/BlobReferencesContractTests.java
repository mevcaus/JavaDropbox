package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.javadropbox.javadropbox.TestDatabase;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.BlobReferences.Prepared;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How references to blobs are counted, and blobs nothing points at deleted (#316), on PostgreSQL
 * and each kind of store. Nothing points rows at blobs yet, so the tests do, as part 3's writes
 * will.
 */
@SpringBootTest
abstract class BlobReferencesContractTests {

  @Autowired BlobReferences references;
  @Autowired BlobStore blobs;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired UserRepository users;
  @MockitoSpyBean FileStore store;

  TransactionTemplate transactions;
  long ada;
  long bob;

  @BeforeEach
  void setUp() {
    transactions = new TransactionTemplate(transactionManager);
    ada = users.save(new User("ada", "unused", "ROLE_USER")).getId();
    bob = users.save(new User("bob", "unused", "ROLE_USER")).getId();
  }

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  @Test
  @DisplayName("new content is put in place as its blob, and a reference to it counted")
  void newContentIsPlacedAndCounted() throws IOException {
    Prepared prepared = prepare("hello", ada);

    assertThat(blobs.stat(prepared.hash())).isPresent();
    add(prepared);

    assertThat(count(prepared.hash())).isEqualTo(1);
    assertThat(released(prepared.hash())).isFalse();
  }

  @Test
  @DisplayName("content the account already holds isn't copied again")
  void ownContentIsNotCopiedAgain() throws IOException {
    Prepared first = prepare("mine", ada);
    add(first);
    pointFileAt(ada, "a.txt", first.hash());
    clearInvocations(store);

    Prepared again = prepare("mine", ada);

    verify(store, never()).replace(anyString(), eq(BlobStore.key(again.hash())));
    assertThat(again.placed()).isFalse();
    add(again);
    references.discard(again);
    assertThat(count(again.hash())).isEqualTo(2);
  }

  @Test
  @DisplayName("content only another account holds is copied, as new content is")
  void othersContentIsCopiedLikeNewContent() throws IOException {
    Prepared adas = prepare("shared", ada);
    add(adas);
    pointFileAt(ada, "a.txt", adas.hash());
    clearInvocations(store);

    Prepared bobs = prepare("shared", bob);
    Prepared fresh = prepare("nobody has this", bob);

    // The same store calls either way, so the upload takes as long either way.
    verify(store, times(1)).replace(anyString(), eq(BlobStore.key(bobs.hash())));
    verify(store, times(1)).replace(anyString(), eq(BlobStore.key(fresh.hash())));
    assertThat(bobs.placed()).isTrue();
    add(bobs);
    assertThat(count(bobs.hash())).isEqualTo(2);
  }

  @Test
  @DisplayName("taking the last reference off releases a blob, and one more is refused")
  void removingReleasesAndRefusesBelowZero() throws IOException {
    Prepared prepared = prepare("short-lived", ada);
    add(prepared);

    inTransaction(
        () -> {
          references.lock(List.of(prepared.hash()));
          references.remove(prepared.hash());
        });

    assertThat(count(prepared.hash())).isZero();
    assertThat(released(prepared.hash())).isTrue();
    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> {
                      references.lock(List.of(prepared.hash()));
                      references.remove(prepared.hash());
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("than they have");
  }

  @Test
  @DisplayName("a count changes only under its blob's lock, taken once per transaction")
  void countsChangeOnlyUnderTheLock() throws IOException {
    Prepared prepared = prepare("locked", ada);

    assertThatThrownBy(() -> inTransaction(() -> references.add(prepared)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("without its lock");
    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> {
                      references.lock(List.of(prepared.hash()));
                      references.lock(List.of(prepared.hash()));
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count(prepared.hash())).isZero();
  }

  @Test
  @DisplayName("a blob the account holds that has gone from the store is put back")
  void goneBlobIsPutBack() throws IOException {
    Prepared first = prepare("put back", ada);
    add(first);
    pointFileAt(ada, "a.txt", first.hash());
    blobs.delete(first.hash());

    Prepared again = prepare("put back", ada);
    add(again);

    assertThat(blobs.stat(again.hash())).isPresent();
    assertThat(count(again.hash())).isEqualTo(2);
  }

  @Test
  @DisplayName("a failed add counts nothing, even though the transaction went on")
  void failedAddCountsNothing() throws IOException {
    Prepared prepared = prepare("gone too soon", ada);
    // Put in place, then gone before the reference is taken: nothing left to put back.
    blobs.delete(prepared.hash());

    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> {
                      references.lock(List.of(prepared.hash()));
                      try {
                        references.add(prepared);
                      } catch (IOException e) {
                        // Swallowed, as a careless caller might: the transaction must still
                        // roll back.
                      }
                    }))
        .isInstanceOf(UnexpectedRollbackException.class);
    assertThat(count(prepared.hash())).isZero();
  }

  @Test
  @DisplayName("a blob released longer ago than the grace period is deleted, row and content")
  void releasedBlobIsDeleted() throws IOException {
    Prepared prepared = prepare("old", ada);
    releasedAgo(prepared.hash(), Duration.ofHours(2));

    assertThat(collector().collect()).isOne();

    assertThat(jdbc.queryForList("SELECT hash FROM blobs", String.class)).isEmpty();
    assertThat(blobs.stat(prepared.hash())).isEmpty();
  }

  @Test
  @DisplayName("a referenced blob, or one released within the grace period, is kept")
  void referencedAndRecentBlobsAreKept() throws IOException {
    Prepared referenced = prepare("referenced", ada);
    add(referenced);
    Prepared recent = prepare("recent", ada);

    // Within the hour's grace: nothing goes.
    releasedAgo(recent.hash(), Duration.ofMinutes(30));
    assertThat(collector().collect()).isZero();
    assertThat(blobs.stat(recent.hash())).isPresent();

    // Past it, only the unreferenced one goes.
    releasedAgo(recent.hash(), Duration.ofHours(2));
    assertThat(collector().collect()).isOne();
    assertThat(blobs.stat(referenced.hash())).isPresent();
    assertThat(blobs.stat(recent.hash())).isEmpty();
    assertThat(jdbc.queryForList("SELECT hash FROM blobs", String.class))
        .containsExactly(referenced.hash());
  }

  @Test
  @DisplayName("a blob an upload claims after the job listed it is kept")
  void blobClaimedAfterListingIsKept() throws IOException {
    Prepared old = prepare("claimed meanwhile", ada);
    releasedAgo(old.hash(), Duration.ofHours(2));
    BlobCollector collector = collector();
    // Between the listing and the delete, an upload of the same content claims it.
    collector.afterListed(
        () -> {
          try {
            references.discard(prepare("claimed meanwhile", bob));
          } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        });

    assertThat(collector.collect()).isZero();

    assertThat(blobs.stat(old.hash())).isPresent();
    assertThat(released(old.hash())).isTrue();
  }

  @Test
  @DisplayName(
      "a blob something points at isn't deleted when its count says nothing does: it's recounted")
  void missedReferenceIsRecounted() throws IOException {
    Prepared prepared = prepare("miscounted", ada);
    pointFileAt(ada, "a.txt", prepared.hash());
    releasedAgo(prepared.hash(), Duration.ofHours(2));

    assertThat(collector().collect()).isZero();

    assertThat(blobs.stat(prepared.hash())).isPresent();
    assertThat(count(prepared.hash())).isOne();
    assertThat(released(prepared.hash())).isFalse();
  }

  @Test
  @DisplayName("another reference to a stored blob is counted without copying it")
  void storedBlobGetsAnotherReference() throws IOException {
    Prepared prepared = prepare("restored", ada);
    add(prepared);
    clearInvocations(store);

    inTransaction(
        () -> {
          references.lock(List.of(prepared.hash()));
          references.add(prepared.hash());
        });

    assertThat(count(prepared.hash())).isEqualTo(2);
    verify(store, never()).replace(anyString(), anyString());
    blobs.delete(prepared.hash());
    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> {
                      references.lock(List.of(prepared.hash()));
                      references.add(prepared.hash());
                    }))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("isn't stored");
  }

  @Test
  @DisplayName("many references come off at once, or none when one blob has too few")
  void manyReferencesComeOffAtOnce() throws IOException {
    Prepared a = prepare("folder file a", ada);
    Prepared b = prepare("folder file b", ada);
    add(a);
    add(a);
    add(b);

    assertThatThrownBy(
            () -> inTransaction(() -> references.removeAll(Map.of(a.hash(), 1, b.hash(), 2))))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count(a.hash())).isEqualTo(2);
    assertThat(count(b.hash())).isOne();

    inTransaction(() -> references.removeAll(Map.of(a.hash(), 1, b.hash(), 1)));

    assertThat(count(a.hash())).isOne();
    assertThat(released(a.hash())).isFalse();
    assertThat(count(b.hash())).isZero();
    assertThat(released(b.hash())).isTrue();
  }

  @Test
  @DisplayName("content is staged and put in place only outside a transaction")
  void prepareRefusesATransaction() {
    assertThatThrownBy(() -> inTransaction(() -> prepare("too late", ada)))
        .isInstanceOf(IllegalStateException.class);
  }

  // The job, with an hour's grace, run by the test rather than on its schedule.
  BlobCollector collector() {
    return new BlobCollector(
        references, blobs, jdbc, transactionManager, Duration.ofHours(1), false);
  }

  void releasedAgo(String hash, Duration ago) {
    jdbc.update(
        "UPDATE blobs SET released_at = now() - make_interval(secs => ?) WHERE hash = ?",
        ago.toSeconds(),
        hash);
  }

  Prepared prepare(String content, long ownerId) throws IOException {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    return references.prepare(new ByteArrayInputStream(bytes), bytes.length, ownerId);
  }

  void add(Prepared prepared) throws IOException {
    inTransaction(
        () -> {
          references.lock(List.of(prepared.hash()));
          references.add(prepared);
        });
  }

  // A file of the account's pointing at the blob, as part 3's uploads will make.
  void pointFileAt(long ownerId, String path, String hash) {
    jdbc.update(
        "INSERT INTO file_metadata (path, filename, size, is_directory, current_version, user_id,"
            + " blob_hash) VALUES (?, ?, 0, false, 1, ?, ?)",
        path,
        path,
        ownerId,
        hash);
  }

  int count(String hash) {
    return jdbc.queryForObject(
        "SELECT reference_count FROM blobs WHERE hash = ?", Integer.class, hash);
  }

  boolean released(String hash) {
    return jdbc.queryForObject(
        "SELECT released_at IS NOT NULL FROM blobs WHERE hash = ?", Boolean.class, hash);
  }

  interface Work {
    void run() throws IOException;
  }

  void inTransaction(Work work) throws IOException {
    try {
      transactions.executeWithoutResult(
          status -> {
            try {
              work.run();
            } catch (IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          });
    } catch (java.io.UncheckedIOException e) {
      throw e.getCause();
    }
  }
}
