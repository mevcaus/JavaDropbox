package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.repository.ListedRow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Rows as importing what is on disk would make them, for tests of search backends that write files
 * and have no database: one row for each item in an account's folder, its size and modification
 * time the store's. Hidden names get no row, as importing leaves them out.
 */
final class RowsFromStore implements IndexableItems.Rows {

  private final FileStore store;

  RowsFromStore(FileStore store) {
    this.store = store;
  }

  @Override
  public List<Long> accounts() {
    try {
      return store.list(StoragePaths.HOMES_DIR).stream()
          .filter(entry -> entry.isDirectory() && entry.name().matches("\\d{1,18}"))
          .map(entry -> Long.parseLong(entry.name()))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public List<ListedRow> of(long account, String path) {
    String home = StoragePaths.HOMES_DIR + "/" + account;
    List<ListedRow> rows = new ArrayList<>();
    try {
      store.walk(
          path.isEmpty() ? home : home + "/" + path,
          entry -> {
            if (entry.key().equals(home)) {
              return FileVisitResult.CONTINUE;
            }
            if (entry.name().startsWith(".")) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            rows.add(
                new ListedRow(
                    entry.key().substring(home.length() + 1),
                    entry.isDirectory(),
                    entry.isDirectory() ? 0 : entry.size(),
                    entry.modified()));
            return FileVisitResult.CONTINUE;
          });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return rows;
  }
}
