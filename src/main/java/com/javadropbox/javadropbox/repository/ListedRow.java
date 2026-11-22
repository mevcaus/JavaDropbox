package com.javadropbox.javadropbox.repository;

import java.time.Instant;

/**
 * What listing an item needs of its row, read without loading the whole entity.
 *
 * @param path in the account's folder
 * @param isDirectory as the row records it; a row with rows below it is a folder whatever it says
 * @param size in bytes, or null
 * @param updatedAt when its content last changed, or null
 * @param blobHash the blob its content is kept as, or null where it's at its own path (#316)
 */
public record ListedRow(
    String path, Boolean isDirectory, Long size, Instant updatedAt, String blobHash) {

  /** A row whose content, if any, is at its own path. */
  public ListedRow(String path, Boolean isDirectory, Long size, Instant updatedAt) {
    this(path, isDirectory, size, updatedAt, null);
  }
}
