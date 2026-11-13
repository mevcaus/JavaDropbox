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

  private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  @DisplayName("rows below the folder are listed in path order, with their size and time")
  void rowsBelowAreListed() {
    List<Item> items =
        ListedItems.below(
            "docs",
            List.of(
                new ListedRow("docs/b.txt", false, 2L, T),
                new ListedRow("docs", true, 0L, T),
                new ListedRow("docs/a.txt", false, 1L, T),
                new ListedRow("docs-other.txt", false, 1L, T),
                new ListedRow("other/c.txt", false, 1L, T)));

    assertThat(items)
        .containsExactly(new Item("docs/a.txt", false, 1, T), new Item("docs/b.txt", false, 2, T));
  }

  @Test
  @DisplayName("a row with rows below it is a folder, and a folder with no row is made up")
  void foldersFromRowsBelow() {
    List<Item> items =
        ListedItems.below(
            "",
            List.of(
                new ListedRow("f", false, 9L, T),
                new ListedRow("f/g.txt", false, 1L, T),
                new ListedRow("a/b/c.txt", false, 1L, T)));

    assertThat(items)
        .containsExactly(
            new Item("a", true, 0, null),
            new Item("a/b", true, 0, null),
            new Item("a/b/c.txt", false, 1, T),
            new Item("f", true, 0, T),
            new Item("f/g.txt", false, 1, T));
  }

  @Test
  @DisplayName("hidden names and empty parts are not listed, nor are folders only they imply")
  void hiddenRowsAreNotListed() {
    List<Item> items =
        ListedItems.below(
            "",
            List.of(
                new ListedRow(".versions/1/v1", false, 1L, T),
                new ListedRow("docs/.env", false, 1L, T),
                new ListedRow("a//b", false, 1L, T),
                new ListedRow("kept.txt", false, null, null)));

    assertThat(items).containsExactly(new Item("kept.txt", false, 0, null));
    assertThat(items.get(0).name()).isEqualTo("kept.txt");
  }

  @Test
  @DisplayName("a folder with no row is made up only below the folder listed, not at or above it")
  void madeUpFoldersStayBelow() {
    assertThat(ListedItems.below("a/b", List.of(new ListedRow("a/b/c/d.txt", false, 1L, T))))
        .extracting(Item::path)
        .containsExactly("a/b/c", "a/b/c/d.txt");
  }
}
