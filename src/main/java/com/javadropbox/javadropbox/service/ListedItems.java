package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.repository.ListedRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The items the file list shows, from an account's rows, for whatever else lists a folder: a
 * folder's zip and the search index. The rules are the file tree's ({@link FileTreeService}):
 *
 * <ul>
 *   <li>a row with a hidden name or an empty part anywhere in its path, left by older releases, is
 *       not listed;
 *   <li>a row with rows below it is a folder, whatever it says;
 *   <li>a folder with no row of its own that rows are below is listed as a folder.
 * </ul>
 */
final class ListedItems {

  private ListedItems() {}

  /**
   * An item as the file list shows it.
   *
   * @param path in the account's folder
   * @param size in bytes; 0 for a folder
   * @param modified when its content last changed, or null, as for a folder with no row
   */
  record Item(String path, boolean folder, long size, Instant modified) {

    String name() {
      return path.substring(path.lastIndexOf('/') + 1);
    }
  }

  /**
   * The items strictly below the folder at {@code top} ("" for the account's root), in path order,
   * from rows that are all at or below it. A row at {@code top} itself is left out, and so is any
   * row outside it.
   */
  static List<Item> below(String top, List<ListedRow> rows) {
    String prefix = top.isEmpty() ? "" : top + "/";
    List<ListedRow> listed =
        rows.stream()
            .filter(row -> isListable(row.path()) && row.path().startsWith(prefix))
            .toList();
    Set<String> folders = new HashSet<>();
    for (ListedRow row : listed) {
      for (String parent = parentOf(row.path());
          !parent.equals(top) && !parent.isEmpty() && folders.add(parent);
          parent = parentOf(parent)) {}
    }
    List<Item> items = new ArrayList<>(listed.size());
    Set<String> withRows = new HashSet<>();
    for (ListedRow row : listed) {
      withRows.add(row.path());
      boolean folder = folders.contains(row.path()) || Boolean.TRUE.equals(row.isDirectory());
      long size = folder || row.size() == null ? 0 : row.size();
      items.add(new Item(row.path(), folder, size, row.updatedAt()));
    }
    for (String folder : folders) {
      if (!withRows.contains(folder)) {
        items.add(new Item(folder, true, 0, null));
      }
    }
    items.sort(Comparator.comparing(Item::path));
    return items;
  }

  /** Whether a row's path is one the file list shows: no hidden name and no empty part in it. */
  static boolean isListable(String path) {
    if (path == null || path.isEmpty()) {
      return false;
    }
    for (String segment : path.split("/", -1)) {
      if (segment.isEmpty() || segment.startsWith(".")) {
        return false;
      }
    }
    return true;
  }

  static String parentOf(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }
}
