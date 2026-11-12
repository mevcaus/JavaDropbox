package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.FileTreeNode;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.PreviewType;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.service.StoragePaths.StoragePath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the file tree the UI browses: what is in one account's folder, read from the account's
 * rows in one query. The database is the list of what exists; the store is never looked at here.
 * Files added to the store outside the app show up once they are imported.
 */
@Service
public class FileTreeService {

  private static final Logger log = LoggerFactory.getLogger(FileTreeService.class);

  private static final Comparator<FileTreeNode> FOLDERS_FIRST_BY_NAME =
      Comparator.comparing(FileTreeNode::getIsDirectory)
          .reversed()
          .thenComparing(FileTreeNode::getName, String.CASE_INSENSITIVE_ORDER);

  private final FileMetadataRepository files;

  public FileTreeService(FileMetadataRepository files) {
    this.files = files;
  }

  /** Everything in an account's folder. */
  @Transactional(readOnly = true)
  public List<FileTreeNode> tree(User user) {
    return build("", files.findAllOf(user.getId()));
  }

  /** The tree below one folder, built the same way: what a shared folder's page lists. */
  @Transactional(readOnly = true)
  public List<FileTreeNode> tree(StoragePath folder) {
    long ownerId = folder.home().userId();
    if (folder.isRoot()) {
      return build("", files.findAllOf(ownerId));
    }
    return build(
        folder.key(), files.findAllBelow(ownerId, FileMetadataRepository.below(folder.key())));
  }

  // The nodes below the folder at top ("" for the account's root), from rows that are all below
  // it. Parents come before what they hold, as shorter paths sort first. A row whose parent has no
  // row of its own (not expected, but not worth hiding the row over) gets a folder node made up
  // for it. A row with rows below it is a folder, whatever it says, so a path is listed once.
  // Rows the app wouldn't accept a path for today, left by older releases (a hidden name, an
  // empty segment), are left out, as walking the store left them out.
  private static List<FileTreeNode> build(String top, List<FileMetadata> rows) {
    List<FileMetadata> listed =
        rows.stream().filter(row -> ListedItems.isListable(row.getPath())).toList();
    Set<String> parents = new HashSet<>();
    // Every folder above a row, not only its parent: a row with rows anywhere below it is a folder.
    for (FileMetadata row : listed) {
      for (String parent = parentKey(row.getPath());
          !parent.isEmpty() && parents.add(parent);
          parent = parentKey(parent)) {}
    }
    List<FileTreeNode> topLevel = new ArrayList<>();
    Map<String, FileTreeNode> folders = new HashMap<>();
    listed.stream()
        .sorted(Comparator.comparingInt((FileMetadata row) -> depth(row.getPath())))
        .forEach(
            row -> {
              FileTreeNode node = node(row, parents.contains(row.getPath()));
              childrenOf(parentKey(row.getPath()), top, row, topLevel, folders).add(node);
              if (node.getIsDirectory()) {
                folders.put(row.getPath(), node);
              }
            });
    finish(topLevel);
    return topLevel;
  }

  private static List<FileTreeNode> childrenOf(
      String parent,
      String top,
      FileMetadata row,
      List<FileTreeNode> topLevel,
      Map<String, FileTreeNode> folders) {
    if (parent.equals(top)) {
      return topLevel;
    }
    FileTreeNode folder = folders.get(parent);
    if (folder == null) {
      log.debug("Listing a folder with no row of its own for the rows below it");
      folder = new FileTreeNode(lastSegment(parent), true, 0, parent);
      folder.setOwnerName(ownerName(row));
      childrenOf(parentKey(parent), top, row, topLevel, folders).add(folder);
      folders.put(parent, folder);
    }
    return folder.getChildren();
  }

  private static FileTreeNode node(FileMetadata row, boolean hasChildren) {
    boolean isDirectory = hasChildren || Boolean.TRUE.equals(row.getIsDirectory());
    FileTreeNode node =
        new FileTreeNode(
            row.getFilename(),
            isDirectory,
            isDirectory || row.getSize() == null ? 0 : row.getSize(),
            row.getPath());
    describe(node, row);
    if (!isDirectory) {
      node.setPreviewType(PreviewType.of(row.getFilename()).orElse(null));
    }
    return node;
  }

  private static void describe(FileTreeNode node, FileMetadata row) {
    node.setId(row.getId());
    node.setCreatedDate(row.getCreatedAt());
    node.setLastModified(row.getUpdatedAt());
    node.setOwnerName(ownerName(row));
  }

  private static String ownerName(FileMetadata row) {
    return row.getOwner().getUsername();
  }

  // Sorts each folder's contents and adds up its size. A folder with no dates of its own takes the
  // latest of what it holds.
  private static void finish(List<FileTreeNode> nodes) {
    for (FileTreeNode node : nodes) {
      if (node.getIsDirectory()) {
        finish(node.getChildren());
        node.setSize(node.getChildren().stream().mapToLong(FileTreeNode::getSize).sum());
        if (node.getLastModified() == null) {
          node.setLastModified(latest(node.getChildren(), FileTreeNode::getLastModified));
        }
        if (node.getCreatedDate() == null) {
          node.setCreatedDate(latest(node.getChildren(), FileTreeNode::getCreatedDate));
        }
      }
    }
    nodes.sort(FOLDERS_FIRST_BY_NAME);
  }

  private static Instant latest(List<FileTreeNode> nodes, Function<FileTreeNode, Instant> date) {
    return nodes.stream()
        .map(date)
        .filter(Objects::nonNull)
        .max(Comparator.naturalOrder())
        .orElse(null);
  }

  private static int depth(String path) {
    return (int) path.chars().filter(c -> c == '/').count();
  }

  private static String parentKey(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }

  private static String lastSegment(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }
}
