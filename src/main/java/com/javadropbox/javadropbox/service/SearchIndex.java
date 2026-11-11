package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.Snippet;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import com.javadropbox.javadropbox.service.StoragePaths.StoragePath;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

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
 * Indexing happens in the background: reporting a change never waits for a file to be read.
 *
 * <p>{@link LuceneSearchIndex}, inside the app, is the one there is.
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
   * folder when empty) that match {@code text}, best first. Every word in it has to be in the
   * item's name or in its text.
   */
  Hits search(Home home, String folderKey, String text, int limit) throws IOException;

  /** Waits until everything reported so far is indexed and searchable. For tests. */
  void awaitIdle(Duration timeout) throws InterruptedException;
}
