package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.DownloadableResource;
import com.javadropbox.javadropbox.dto.FileTreeNode;
import com.javadropbox.javadropbox.dto.Timestamps;
import com.javadropbox.javadropbox.model.FileHistory;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.FileVersion;
import com.javadropbox.javadropbox.model.RestoreMode;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileHistoryRepository;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.FileVersionRepository;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileServingService {

  private static final String FALLBACK_OWNER = "system";

  @Value("${javadropbox.versions.max-retained:10}")
  private int maxVersions;

  private final FileMetadataRepository fileMetadataRepository;
  private final FileHistoryRepository fileHistoryRepository;
  private final FileVersionRepository fileVersionRepository;
  private final AuthService authService;
  private final StoragePaths storagePaths;

  public FileServingService(
      FileMetadataRepository fileMetadataRepository,
      FileHistoryRepository fileHistoryRepository,
      FileVersionRepository fileVersionRepository,
      AuthService authService,
      StoragePaths storagePaths) {
    this.fileMetadataRepository = fileMetadataRepository;
    this.fileHistoryRepository = fileHistoryRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.authService = authService;
    this.storagePaths = storagePaths;
  }

  public String getServingDirectory() {
    return storagePaths.root().toString();
  }

  public boolean isValidDirectory() {
    return Files.isDirectory(storagePaths.root());
  }

  public boolean canRead() {
    return Files.isReadable(storagePaths.root());
  }

  public boolean canWrite() {
    return Files.isWritable(storagePaths.root());
  }

  public List<FileTreeNode> getDirectoryTree() {
    File rootDir = storagePaths.root().toFile();
    if (!isValidDirectory() || !canRead()) {
      return Collections.emptyList();
    }
    return buildTreeRecursively(rootDir, "");
  }

  private List<FileTreeNode> buildTreeRecursively(File directory, String relativePath) {
    File[] files = directory.listFiles();
    if (files == null) {
      return Collections.emptyList();
    }

    List<FileTreeNode> nodes = new ArrayList<>();

    for (File file : files) {
      // Symlinks are skipped: one pointing outside the serving directory would expose it, and one
      // pointing at an ancestor would recurse forever.
      if (file.getName().startsWith(".") || Files.isSymbolicLink(file.toPath())) {
        continue;
      }
      String currentPath =
          relativePath.isEmpty() ? file.getName() : relativePath + "/" + file.getName();
      FileTreeNode node =
          new FileTreeNode(file.getName(), file.isDirectory(), file.length(), currentPath);

      populateNodeMetadata(node, currentPath, file);

      if (file.isDirectory()) {
        List<FileTreeNode> children = buildTreeRecursively(file, currentPath);
        node.setChildren(children);

        long totalSize = children.stream().mapToLong(FileTreeNode::getSize).sum();

        node.setSize(totalSize);
      }

      nodes.add(node);
    }

    nodes.sort(
        Comparator.comparing(FileTreeNode::getIsDirectory)
            .reversed()
            .thenComparing(FileTreeNode::getName, String.CASE_INSENSITIVE_ORDER));

    return nodes;
  }

  private void populateNodeMetadata(FileTreeNode node, String currentPath, File file) {
    Optional<FileMetadata> metadataOpt = fileMetadataRepository.findByPath(currentPath);

    if (metadataOpt.isPresent()) {
      FileMetadata meta = metadataOpt.get();
      node.setId(meta.getId());
      node.setCreatedDate(Timestamps.toInstant(meta.getCreatedAt()));
      node.setLastModified(Timestamps.toInstant(meta.getUpdatedAt()));
      if (meta.getOwner() != null) {
        node.setOwnerName(meta.getOwner().getUsername());
      }
    } else {
      populateNodeFromFileSystem(node, file);
    }
  }

  private void populateNodeFromFileSystem(FileTreeNode node, File file) {
    try {
      BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
      // File times are already absolute instants, so they need no zone applied.
      node.setCreatedDate(attrs.creationTime().toInstant());
      node.setLastModified(attrs.lastModifiedTime().toInstant());
      node.setOwnerName(FALLBACK_OWNER);
    } catch (IOException e) {
      node.setCreatedDate(null);
      node.setLastModified(null);
      node.setOwnerName(FALLBACK_OWNER);
    }
  }

  /**
   * @throws com.javadropbox.javadropbox.exception.BadRequestException for an invalid path or the
   *     root, which can never be shared
   */
  public boolean pathExists(String relativePath) {
    return Files.exists(storagePaths.resolveItem(relativePath).path());
  }

  public DownloadableResource getResourceForPath(String relativePath) throws IOException {
    Path fullPath = storagePaths.resolveItem(relativePath).path();

    File file = fullPath.toFile();
    if (!file.exists()) {
      throw new FileNotFoundException("File not found: " + relativePath);
    }

    updateLastAccessedTime(relativePath);

    if (file.isDirectory()) {
      return createZipResourceForDirectory(file);

    } else {
      return createResourceForFile(fullPath, file);
    }
  }

  private void updateLastAccessedTime(String relativePath) {
    try {
      Optional<FileMetadata> metadata = fileMetadataRepository.findByPath(relativePath);
      metadata.ifPresent(
          m -> {
            m.setLastAccessed(LocalDateTime.now());
            fileMetadataRepository.save(m);
          });
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private DownloadableResource createZipResourceForDirectory(File directory) throws IOException {
    String zipFilename = directory.getName() + ".zip";
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

    try (ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream)) {
      zipDirectory(directory, directory.getName(), zipOutputStream);
    }

    ByteArrayInputStream byteArrayInputStream =
        new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
    Resource zipResource = new InputStreamResource(byteArrayInputStream);
    return new DownloadableResource(zipResource, zipFilename, "application/zip");
  }

  private DownloadableResource createResourceForFile(Path fullPath, File file) throws IOException {
    Resource resource = new org.springframework.core.io.UrlResource(fullPath.toUri());
    String contentType = Files.probeContentType(fullPath);
    if (contentType == null) {
      contentType = "application/octet-stream";
    }
    return new DownloadableResource(resource, file.getName(), contentType);
  }

  private void zipDirectory(File folder, String parentPath, ZipOutputStream zos)
      throws IOException {
    File[] files = folder.listFiles();
    if (files == null) {
      throw new IOException("Could not list " + folder);
    }
    for (File file : files) {
      if (Files.isSymbolicLink(file.toPath())) {
        continue;
      }
      if (file.isDirectory()) {
        zipDirectory(file, parentPath + "/" + file.getName(), zos);
        continue;
      }
      ZipEntry zipEntry = new ZipEntry(parentPath + "/" + file.getName());
      zos.putNextEntry(zipEntry);
      Files.copy(file.toPath(), zos);
      zos.closeEntry();
    }
  }

  public void saveUploadedFiles(MultipartFile[] files, String subpath) throws IOException {
    StoragePaths.StoragePath folder = storagePaths.resolve(subpath);
    Path destinationFolder = folder.path();

    if (!Files.exists(destinationFolder)) {
      Files.createDirectories(destinationFolder);
    }

    User currentUser = authService.getMainUser().orElse(null);

    for (MultipartFile file : files) {
      if (file.isEmpty()) {
        continue;
      }

      String originalFilename = file.getOriginalFilename();

      try {
        StoragePaths.StoragePath target = storagePaths.resolveChild(folder, originalFilename);
        Path destinationFile = target.path();
        String relativeFilePath = target.key();
        Optional<FileMetadata> existingMetadata =
            fileMetadataRepository.findByPath(relativeFilePath);

        if (existingMetadata.isPresent() && Files.exists(destinationFile)) {
          saveVersion(existingMetadata.get(), destinationFile);
        }

        Files.copy(file.getInputStream(), destinationFile, StandardCopyOption.REPLACE_EXISTING);

        // DB Integration: Success
        try {

          FileMetadata metadata =
              fileMetadataRepository
                  .findByPath(relativeFilePath)
                  .orElse(
                      new FileMetadata(
                          relativeFilePath, originalFilename, file.getSize(), false, currentUser));

          metadata.setSize(file.getSize());
          metadata.setUpdatedAt(java.time.LocalDateTime.now());

          FileMetadata savedMetadata = fileMetadataRepository.save(metadata);

          FileHistory history =
              new FileHistory(savedMetadata, FileHistory.ChangeType.UPLOAD, currentUser);
          fileHistoryRepository.save(history);

        } catch (Exception e) {
          // Log error but don't fail the request if just DB fails, or should i?
          // For now just log print stack trace as before for DB errors,
          // BUT if the file copy itself failed, i want to capture that in history if
          // possible (though i might not have a file record yet).
          System.err.println("Failed to save DB metadata for: " + originalFilename);
          e.printStackTrace();
        }

      } catch (Exception e) {
        // Log Failure
        String failedPath = subpath.isEmpty() ? originalFilename : subpath + "/" + originalFilename;
        FileHistory failure =
            new FileHistory(
                failedPath,
                originalFilename,
                FileHistory.ChangeType.UPLOAD,
                currentUser,
                false,
                e.getMessage());
        fileHistoryRepository.save(failure);
        throw e; // Rethrow to notify controller
      }
    }
  }

  private void saveVersion(FileMetadata metadata, Path currentFilePath) throws IOException {
    File currentFile = currentFilePath.toFile();
    Path versionsDir = storagePaths.versionsDir();
    if (!Files.exists(versionsDir)) {
      Files.createDirectories(versionsDir);
    }

    int currentVer = metadata.getCurrentVersion() != null ? metadata.getCurrentVersion() : 1;
    String versionFilename = metadata.getFilename() + ".v" + currentVer;

    Path versionPath = versionsDir.resolve(versionFilename);
    while (Files.exists(versionPath)) {
      currentVer++;
      versionFilename = metadata.getFilename() + ".v" + currentVer;
      versionPath = versionsDir.resolve(versionFilename);
      metadata.setCurrentVersion(currentVer);
    }

    Files.move(currentFile.toPath(), versionPath, StandardCopyOption.REPLACE_EXISTING);

    FileVersion fileVersion =
        new FileVersion(
            metadata,
            currentVer,
            versionFilename,
            Files.size(versionPath),
            authService.getMainUser().orElse(null));
    fileVersionRepository.save(fileVersion);

    metadata.setCurrentVersion(currentVer + 1);
    fileMetadataRepository.save(metadata);

    List<FileVersion> versions =
        fileVersionRepository.findByFileMetadataOrderByVersionDesc(metadata);
    if (versions.size() > maxVersions) {
      for (int i = maxVersions; i < versions.size(); i++) {
        FileVersion oldVersion = versions.get(i);
        try {
          Files.deleteIfExists(versionsDir.resolve(oldVersion.getStoredFilename()));
          fileVersionRepository.delete(oldVersion);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  public void restoreVersion(Long fileId, Integer version, RestoreMode mode) throws IOException {
    FileMetadata metadata =
        fileMetadataRepository
            .findById(fileId)
            .orElseThrow(() -> new FileNotFoundException("File not found"));

    List<FileVersion> versions =
        fileVersionRepository.findByFileMetadataOrderByVersionDesc(metadata);
    FileVersion versionToRestore =
        versions.stream()
            .filter(v -> v.getVersion().equals(version))
            .findFirst()
            .orElseThrow(() -> new FileNotFoundException("Version not found"));

    Path versionsDir = storagePaths.versionsDir();
    Path versionPath = versionsDir.resolve(versionToRestore.getStoredFilename());

    if (!Files.exists(versionPath)) {
      throw new FileNotFoundException("Version file missing on disk");
    }

    User currentUser = authService.getMainUser().orElse(null);

    if (mode == RestoreMode.COPY) {
      // Restore as COPY
      String originalName = metadata.getFilename();
      String nameWithoutExt = originalName;
      String ext = "";
      int lastDot = originalName.lastIndexOf(".");
      if (lastDot > 0) {
        nameWithoutExt = originalName.substring(0, lastDot);
        ext = originalName.substring(lastDot);
      }

      String newFilename = nameWithoutExt + "_v" + version + ext;
      String newRelativePath = "";

      // Handle parent directory in path
      Path oldPath = Paths.get(metadata.getPath());
      if (oldPath.getParent() != null) {
        newRelativePath = oldPath.getParent().toString() + "/" + newFilename;
      } else {
        newRelativePath = newFilename;
      }

      Path newFullPath = storagePaths.resolveItem(newRelativePath).path();

      Files.copy(versionPath, newFullPath, StandardCopyOption.REPLACE_EXISTING);

      // Create new metadata
      FileMetadata newMetadata =
          new FileMetadata(
              newRelativePath, newFilename, Files.size(newFullPath), false, currentUser);
      fileMetadataRepository.save(newMetadata);

      FileHistory history =
          new FileHistory(newMetadata, FileHistory.ChangeType.UPLOAD, currentUser);
      history.setErrorMessage("Restored from " + metadata.getFilename() + " (v" + version + ")");
      fileHistoryRepository.save(history);

    } else {
      // Restore as OVERWRITE
      Path currentFilePath = storagePaths.resolveItem(metadata.getPath()).path();
      File currentFile = currentFilePath.toFile();

      if (currentFile.exists()) {
        saveVersion(metadata, currentFilePath);
      }

      Files.copy(versionPath, currentFilePath, StandardCopyOption.REPLACE_EXISTING);

      metadata.setSize(Files.size(currentFilePath));
      metadata.setUpdatedAt(LocalDateTime.now());
      fileMetadataRepository.save(metadata);

      FileHistory history = new FileHistory(metadata, FileHistory.ChangeType.UPLOAD, currentUser);
      history.setErrorMessage("Restored from version " + version);
      fileHistoryRepository.save(history);
    }
  }

  public void deleteItem(String relativePath) throws IOException {
    User currentUser = authService.getMainUser().orElse(null);
    String filename = "unknown";

    try {
      Path fullPath = storagePaths.resolveItem(relativePath).path();
      File itemToDelete = fullPath.toFile();
      if (!itemToDelete.exists()) {
        throw new FileNotFoundException("Item not found: " + relativePath);
      }

      filename = itemToDelete.getName();

      if (itemToDelete.isDirectory()) {
        deleteRecursively(itemToDelete);
        recordDeletion(relativePath, filename, currentUser);
      } else {
        if (!itemToDelete.delete()) {
          throw new IOException("Failed to delete file: " + relativePath);
        }
        recordDeletion(relativePath, filename, currentUser);
      }
    } catch (Exception e) {
      FileHistory failure =
          new FileHistory(
              relativePath,
              filename,
              FileHistory.ChangeType.DELETE,
              currentUser,
              false,
              e.getMessage());
      fileHistoryRepository.save(failure);
      throw e;
    }
  }

  private void recordDeletion(String path, String filename, User user) {
    try {
      Optional<FileMetadata> metadataOpt = fileMetadataRepository.findByPath(path);
      if (metadataOpt.isPresent()) {
        FileMetadata metadata = metadataOpt.get();

        // Archive history before deleting metadata
        FileHistory history = new FileHistory(path, filename, FileHistory.ChangeType.DELETE, user);
        fileHistoryRepository.save(history);

        fileMetadataRepository.delete(metadata);
      } else {
        // Even if metadata missing, record history of deletion attempt/success
        FileHistory history = new FileHistory(path, filename, FileHistory.ChangeType.DELETE, user);
        fileHistoryRepository.save(history);
      }
    } catch (Exception e) {
      System.err.println("Failed to update DB for deletion: " + path);
      e.printStackTrace();
    }
  }

  private void deleteRecursively(File file) throws IOException {
    // Delete a symlink itself, never what it points to.
    if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
      File[] entries = file.listFiles();
      if (entries != null) {
        for (File entry : entries) {
          deleteRecursively(entry);
        }
      }
    }
    if (!file.delete()) {
      throw new IOException("Failed to delete: " + file);
    }
  }

  public void createDirectory(String relativePath, String directoryName) throws IOException {
    User currentUser = authService.getMainUser().orElse(null);

    try {
      StoragePaths.StoragePath target =
          storagePaths.resolveChild(storagePaths.resolve(relativePath), directoryName);
      Path newDirPath = target.path();

      if (Files.exists(newDirPath)) {
        throw new IOException("Directory already exists: " + directoryName);
      }

      Files.createDirectories(newDirPath);

      try {
        FileMetadata metadata =
            new FileMetadata(target.key(), directoryName, 0L, true, currentUser);
        FileMetadata savedMetadata = fileMetadataRepository.save(metadata);

        FileHistory history =
            new FileHistory(savedMetadata, FileHistory.ChangeType.CREATE_FOLDER, currentUser);
        fileHistoryRepository.save(history);

      } catch (Exception e) {
        System.err.println("Failed to save DB metadata for dir: " + directoryName);
        e.printStackTrace();
      }

    } catch (Exception e) {
      String fullRelativePath =
          relativePath.isEmpty() ? directoryName : relativePath + "/" + directoryName;
      FileHistory failure =
          new FileHistory(
              fullRelativePath,
              directoryName,
              FileHistory.ChangeType.CREATE_FOLDER,
              currentUser,
              false,
              e.getMessage());
      fileHistoryRepository.save(failure);
      throw e;
    }
  }
}
