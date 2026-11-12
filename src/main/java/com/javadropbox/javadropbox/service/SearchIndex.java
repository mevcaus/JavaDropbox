package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.Snippet;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import com.javadropbox.javadropbox.service.StoragePaths.StoragePath;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * An index of every file's and folder's name, and of the text in files' contents (see {@link
 * TextExtractor}), in every account's folder. Every search is confined to one account's. The {@link
 * FileStore} stays the source of truth: the index only mirrors it, and catches up with it
 *
 * <ul>
 *   <li>after each change the app makes ({@link #changed}), once its transaction has finished;
 *   <li>at startup and from time to time ({@link #reconcile}), for files added, changed or removed
 *       outside the app;
 *   <li>when a search turns up an item that is no longer there, which SearchService reports as
 *       changed.
 * </ul>
 *
 * Indexing happens in the background. Reporting a change or asking for a reconcile returns at once
 * and never throws: an upload or a delete must not wait for the index, nor fail because of it.
 * BackgroundSearchIndex does this, and schedules the reconciles, for every implementation.
 *
 * <p>Items are keyed by their account and their path in its folder, and an implementation must only
 * ever index what is inside an account's folder: not the accounts' folders themselves, not files
 * beside them, not the app's own folder, hidden names or anything behind a symbolic link.
 * IndexableItems walks the store that way for every implementation. Each implementation is checked
 * by the tests in SearchIndexContractTests, and by SearchIntegrationTests run against it, which
 * cover what is hidden and what a search returns.
 */
public interface SearchIndex {

  /**
   * An item a search found: its path in the account's folder, and the passage of its text that
   * matched, if any did.
   */
  record Hit(String path, Snippet snippet) {}

  /**
   * The best hits, how many items matched in all, and whether the index had caught up with the
   * store since the server started.
   */
  record Hits(List<Hit> hits, long total, boolean complete) {}

  /** Like {@link #changed(Home, String)}, for a resolved item. */
  default void changed(StoragePath item) {
    changed(item.home(), item.key());
  }

  /**
   * Reads the item at {@code key} in an account's folder, and everything below it, from the store
   * again: what is there now is indexed afresh and what is gone is dropped. Called inside a
   * transaction, this waits for the transaction to finish, whichever way it does: a rollback puts
   * the store back, and the index then matches that.
   *
   * <p>{@code key} may no longer resolve to anything: SearchService reports hits it could not
   * resolve, which may be gone or behind a symbolic link by now. Whatever the index has under it is
   * then dropped, and nothing outside the account's folder is read.
   */
  void changed(Home home, String key);

  /** Like {@link #changed(Home, String)} for every account's whole folder. */
  void changedEverywhere();

  /**
   * Brings the whole index in step with the store, reading again only the files that changed since
   * they were indexed.
   */
  void reconcile();

  /**
   * The items below the folder at {@code folderKey} in an account's folder (the account's whole
   * folder when empty) that match {@code text}, best first. The folder itself is not a hit.
   *
   * <p>Every word typed has to be found, in the item's name or in its text:
   *
   * <ul>
   *   <li>in the name, anywhere within it, ignoring case and accents;
   *   <li>in the text, as a whole word, or as a phrase when the word holds several, such as
   *       "e-mail"; the last word typed, if it is one of three letters or more, also as the start
   *       of a longer one, so results come up while it is being typed.
   * </ul>
   *
   * Wildcard characters match only themselves. Name matches rank above text matches, and text with
   * the words together above text with them apart. A hit found by its name alone has no snippet.
   *
   * <p>Each hit's path is relative to the account's folder. At most {@code limit} hits are
   * returned, while {@link Hits#total} counts every match.
   *
   * @throws IOException if the index can't be read
   * @throws com.javadropbox.javadropbox.exception.ServiceUnavailableException if a search service
   *     the index is kept in can't answer for now, having logged why
   */
  Hits search(Home home, String folderKey, String text, int limit) throws IOException;

  /**
   * What last kept the index from taking changes or answering searches, until one gets through
   * again; empty when nothing has. For the health endpoint.
   */
  default Optional<String> problem() {
    return Optional.empty();
  }

  /** Waits until everything reported so far is indexed and searchable. For tests. */
  void awaitIdle(Duration timeout) throws InterruptedException;
}
