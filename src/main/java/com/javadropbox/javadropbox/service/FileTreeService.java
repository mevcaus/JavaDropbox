package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.FileTreeNode;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the file tree the UI browses. The disk is the source of truth for what exists; the
 * database adds ids, owners and dates for items the app created.
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
  private final FileMetadataRepository files;

  public FileTreeService(StoragePaths storagePaths, FileMetadataRepository files) {
    this.storagePaths = storagePaths;
    this.files = files;
  }

  @Transactional(readOnly = true)
  public List<FileTreeNode> tree() {
    // One query for every row, rather than one per file on disk.
    Map<String, FileMetadata> metadata =
        files.findAllWithOwner().stream()
            .collect(Collectors.toMap(FileMetadata::getPath, Function.identity()));
    return children(storagePaths.root(), "", metadata);
  }

  private List<FileTreeNode> children(Path folder, String key, Map<String, FileMetadata> metadata) {
    List<FileTreeNode> nodes = new ArrayList<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
      for (Path entry : entries) {
        String name = entry.getFileName().toString();
        // Hidden entries include the app's own directories. Symlinks are skipped: one pointing
        // outside the serving directory would expose it, one pointing at an ancestor would loop.
        if (name.startsWith(".") || Files.isSymbolicLink(entry)) {
          continue;
        }
        String childKey = key.isEmpty() ? name : key + "/" + name;
        try {
          nodes.add(node(entry, name, childKey, metadata));
        } catch (IOException e) {
          log.warn("Skipping {} in the file tree: {}", entry, e.toString());
        }
      }
    } catch (IOException e) {
      log.warn("Could not list {}: {}", folder, e.toString());
    }
    nodes.sort(FOLDERS_FIRST_BY_NAME);
    return nodes;
  }

  private FileTreeNode node(Path entry, String name, String key, Map<String, FileMetadata> metadata)
      throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    FileTreeNode node = new FileTreeNode(name, attributes.isDirectory(), attributes.size(), key);

    FileMetadata row = metadata.get(key);
    if (row != null) {
      node.setId(row.getId());
      node.setCreatedDate(row.getCreatedAt());
      node.setLastModified(row.getUpdatedAt());
      node.setOwnerName(row.getOwner() != null ? row.getOwner().getUsername() : UNTRACKED_OWNER);
    } else {
      node.setCreatedDate(attributes.creationTime().toInstant());
      node.setLastModified(attributes.lastModifiedTime().toInstant());
      node.setOwnerName(UNTRACKED_OWNER);
    }

    if (attributes.isDirectory()) {
      List<FileTreeNode> children = children(entry, key, metadata);
      node.setChildren(children);
      node.setSize(children.stream().mapToLong(FileTreeNode::getSize).sum());
    }
    return node;
  }
}
