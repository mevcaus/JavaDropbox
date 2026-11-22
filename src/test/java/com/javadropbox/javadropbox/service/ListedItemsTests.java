package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.repository.ListedRow;
import com.javadropbox.javadropbox.service.ListedItems.Item;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Items listed from rows")
class ListedItemsTests {

  // Rows and items whose content, if any, is at its own path.
  private static ListedRow row(String path, Boolean isDirectory, Long size, Instant updatedAt) {
    return new ListedRow(path, isDirectory, size, updatedAt, null);
  }

  private static Item item(String path, boolean folder, long size, Instant modified) {
    return new Item(path, folder, size, modified, null);
  }

  private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  @DisplayName("rows below the folder are listed in path order, with their size and time")
  void rowsBelowAreListed() {
    List<Item> items =
        ListedItems.below(
            "docs",
            List.of(
                row("docs/b.txt", false, 2L, T),
                row("docs", true, 0L, T),
                row("docs/a.txt", false, 1L, T),
                row("docs-other.txt", false, 1L, T),
                row("other/c.txt", false, 1L, T)));

    assertThat(items)
        .containsExactly(item("docs/a.txt", false, 1, T), item("docs/b.txt", false, 2, T));
  }

  @Test
  @DisplayName("a row with rows below it is a folder, and a folder with no row is made up")
  void foldersFromRowsBelow() {
    List<Item> items =
        ListedItems.below(
            "",
            List.of(
                row("f", false, 9L, T),
                row("f/g.txt", false, 1L, T),
                row("a/b/c.txt", false, 1L, T)));

    assertThat(items)
        .containsExactly(
            item("a", true, 0, null),
            item("a/b", true, 0, null),
            item("a/b/c.txt", false, 1, T),
            item("f", true, 0, T),
            item("f/g.txt", false, 1, T));
  }

  @Test
  @DisplayName("hidden names and empty parts are not listed, nor are folders only they imply")
  void hiddenRowsAreNotListed() {
    List<Item> items =
        ListedItems.below(
            "",
            List.of(
                row(".versions/1/v1", false, 1L, T),
                row("docs/.env", false, 1L, T),
                row("a//b", false, 1L, T),
                row("kept.txt", false, null, null)));

    assertThat(items).containsExactly(item("kept.txt", false, 0, null));
    assertThat(items.get(0).name()).isEqualTo("kept.txt");
  }

  @Test
  @DisplayName("a folder with no row is made up only below the folder listed, not at or above it")
  void madeUpFoldersStayBelow() {
    assertThat(ListedItems.below("a/b", List.of(row("a/b/c/d.txt", false, 1L, T))))
        .extracting(Item::path)
        .containsExactly("a/b/c", "a/b/c/d.txt");
  }
}
