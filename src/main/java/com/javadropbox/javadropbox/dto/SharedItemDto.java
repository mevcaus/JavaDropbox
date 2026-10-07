package com.javadropbox.javadropbox.dto;

import com.javadropbox.javadropbox.model.PreviewType;
import java.time.Instant;
import java.util.List;

/**
 * What a share link opens, for the page someone with the link sees before downloading. Public, so
 * it carries names only: no paths, ids or owners, which would tell a stranger about the rest of the
 * owner's storage.
 *
 * @param previewType how the file can be shown in the browser; null for a folder, or a file that
 *     can only be downloaded
 * @param contents what a folder holds, folders first, each sorted by name; null for a file
 */
public record SharedItemDto(
    String name,
    boolean isDirectory,
    long size,
    Instant lastModified,
    PreviewType previewType,
    Instant expiresAt,
    List<Entry> contents) {

  /** One item inside a shared folder. {@code children} is null for a file. */
  public record Entry(
      String name, boolean isDirectory, long size, Instant lastModified, List<Entry> children) {

    public static List<Entry> fromTree(List<FileTreeNode> nodes) {
      return nodes.stream()
          .map(
              node ->
                  new Entry(
                      node.getName(),
                      node.getIsDirectory(),
                      node.getSize(),
                      node.getLastModified(),
                      node.getIsDirectory() ? fromTree(node.getChildren()) : null))
          .toList();
    }
  }
}
