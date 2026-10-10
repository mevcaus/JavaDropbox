package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.FileTreeNode;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.PreviewType;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.service.FileStore.Entry;
import com.javadropbox.javadropbox.service.StoragePaths.StoragePath;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the file tree the UI browses: what is in one account's folder. The {@link FileStore} is
 * the source of truth for what exists; the database adds ids, owners and dates for items the app
 * created.
 */
@Service
public class FileTreeService {

  private static final String UNTRACKED_OWNER = "system";

  private static final Logger log = LoggerFactory.getLogger(FileTreeService.class);

  private static final Comparator<FileTreeNode> FOLDERS_FIRST_BY_NAME =
      Comparator.comparing(FileTreeNode::getIsDirectory)
          .reversed()
          .thenComparing(FileTreeNode::getName, String.CASE_INSENSITIVE_ORDER);

  private final StoragePaths storagePaths;
  private final FileStore store;
  private final FileMetadataRepository files;

  public FileTreeService(StoragePaths storagePaths, FileStore store, FileMetadataRepository files) {
    this.storagePaths = storagePaths;
    this.store = store;
    this.files = files;
  }

  /** Everything in an account's folder. */
  @Transactional(readOnly = true)
  public List<FileTreeNode> tree(User user) {
    StoragePath folder = storagePaths.home(user).resolve("");
    return tree(folder);
  }

  /** The tree below one folder, built the same way: what a shared folder's page lists. */
  @Transactional(readOnly = true)
  public List<FileTreeNode> tree(StoragePath folder) {
    Map<String, FileMetadata> metadata = metadata(folder.home().userId());
    // The folder's key in the store, and each node's key in the account's folder, both end where
    // the part below the folder starts.
    String top = folder.storeKey();
    String homePrefix = folder.home().key() + "/";
    List<FileTreeNode> topLevel = new ArrayList<>();
    Map<String, FileTreeNode> folders = new HashMap<>();
    try {
      // Folders come before what they hold, so each item's parent is there before it.
      store.walk(
          top,
          entry -> {
            if (entry.key().equals(top)) {
              return FileVisitResult.CONTINUE;
            }
            // Hidden entries include upload scratch files and the app's own folders.
            if (entry.name().startsWith(".")) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            String parentKey = entry.key().substring(0, entry.key().lastIndexOf('/'));
            FileTreeNode parent = folders.get(parentKey);
            if (parent == null && !parentKey.equals(top)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            String key = entry.key().substring(homePrefix.length());
            FileTreeNode node = node(entry, key, metadata);
            (parent == null ? topLevel : parent.getChildren()).add(node);
            if (entry.isDirectory()) {
              folders.put(entry.key(), node);
            }
            return FileVisitResult.CONTINUE;
          });
    } catch (IOException e) {
      log.warn("Could not list {}: {}", top, e.toString());
    }
    finish(topLevel);
    return topLevel;
  }

  // One query for every row, rather than one per item stored.
  private Map<String, FileMetadata> metadata(long ownerId) {
    return files.findAllOf(ownerId).stream()
        .collect(Collectors.toMap(FileMetadata::getPath, Function.identity()));
  }

  private static FileTreeNode node(Entry entry, String key, Map<String, FileMetadata> metadata) {
    FileTreeNode node = new FileTreeNode(entry.name(), entry.isDirectory(), entry.size(), key);

    FileMetadata row = metadata.get(key);
    if (row != null) {
      node.setId(row.getId());
      node.setCreatedDate(row.getCreatedAt());
      node.setLastModified(row.getUpdatedAt());
      node.setOwnerName(row.getOwner() != null ? row.getOwner().getUsername() : UNTRACKED_OWNER);
    } else {
      node.setCreatedDate(entry.created());
      node.setLastModified(entry.modified());
      node.setOwnerName(UNTRACKED_OWNER);
    }
    if (!entry.isDirectory()) {
      node.setPreviewType(PreviewType.of(entry.name()).orElse(null));
    }
    return node;
  }

  // Sorts each folder's contents and adds up its size. A folder whose dates the store does not
  // know, as in S3 where one exists only because something is in it, takes the latest of what is.
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
}
