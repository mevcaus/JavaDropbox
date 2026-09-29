package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.Download;
import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.exception.ConflictException;
import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.model.FileHistory.ChangeType;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.RestoreMode;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.service.StoragePaths.StoragePath;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Changes to stored files and folders. Each change runs in one database transaction, and the disk
 * work is ordered so that a failure part-way leaves the previous content in place: new content is
 * written to a scratch file first and only renamed over the old one at the end.
 *
 * <p>Failures are logged to the history in a transaction of their own after the change's
 * transaction has rolled back.
 */
@Service
public class FileService {

  private static final int MAX_COPY_NAME_ATTEMPTS = 1000;

  private final StoragePaths storagePaths;
  private final FileMetadataRepository files;
  private final FileVersionService versions;
  private final FileHistoryService history;
  private final AuthService authService;
  private final TransactionTemplate transactions;

  public FileService(
      StoragePaths storagePaths,
      FileMetadataRepository files,
      FileVersionService versions,
      FileHistoryService history,
      AuthService authService,
      PlatformTransactionManager transactionManager) {
    this.storagePaths = storagePaths;
    this.files = files;
    this.versions = versions;
    this.history = history;
    this.authService = authService;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Stores uploaded files in a folder. Each file is committed on its own, so a failure on one does
   * not undo the ones before it; the failing file and everything after it are not stored.
   */
  public void upload(MultipartFile[] uploads, String folderPath) throws IOException {
    User user = authService.currentUser();
    StoragePath folder = storagePaths.resolve(folderPath);
    Files.createDirectories(folder.path());

    for (MultipartFile upload : uploads) {
      if (upload.isEmpty()) {
        continue;
      }
      String name = upload.getOriginalFilename();
      try {
        store(upload, storagePaths.resolveChild(folder, name), user);
      } catch (IOException | RuntimeException e) {
        history.recordFailure(childKey(folder, name), name, ChangeType.UPLOAD, user, e);
        throw e;
      }
    }
  }

  private void store(MultipartFile upload, StoragePath target, User user) throws IOException {
    if (Files.isDirectory(target.path())) {
      throw new ConflictException("A folder named \"" + target.name() + "\" already exists here");
    }

    Path scratch = StorageFiles.tempFileBeside(target.path());
    try {
      try (InputStream in = upload.getInputStream()) {
        Files.copy(in, scratch, StandardCopyOption.REPLACE_EXISTING);
      }
      long size = Files.size(scratch);

      inTransaction(
          () -> {
            boolean replacing = Files.exists(target.path());
            FileMetadata file =
                replacing ? existingOrNew(target, size, user) : claim(target, false, size, user);
            if (replacing) {
              versions.archive(file, target.path(), user);
            }
            StorageFiles.moveIntoPlace(scratch, target.path());

            file.setSize(size);
            file.setUpdatedAt(Instant.now());
            history.recordSuccess(file, ChangeType.UPLOAD, user, null);
          });
    } finally {
      Files.deleteIfExists(scratch);
    }
  }

  /** Deletes a file, or a folder with everything in it, along with their metadata and versions. */
  public void delete(String path) throws IOException {
    User user = authService.currentUser();
    try {
      StoragePath target = storagePaths.resolveItem(path);
      if (!Files.exists(target.path(), LinkOption.NOFOLLOW_LINKS)) {
        throw new NotFoundException("Not found: " + target.key());
      }

      inTransaction(
          () -> {
            List<FileMetadata> rows = new ArrayList<>();
            files.findByPath(target.key()).ifPresent(rows::add);
            rows.addAll(files.findByPathStartingWith(target.key() + "/"));
            rows.forEach(versions::discardAll);
            files.deleteAll(rows);
            // Surface any database problem before anything irreversible happens on disk.
            files.flush();

            StorageFiles.deleteRecursively(target.path());
            history.recordDeletion(target.key(), target.name(), user);
          });
    } catch (IOException | RuntimeException e) {
      history.recordFailure(path, lastSegment(path), ChangeType.DELETE, user, e);
      throw e;
    }
  }

  public void createFolder(String parentPath, String name) throws IOException {
    User user = authService.currentUser();
    try {
      StoragePath parent = storagePaths.resolve(parentPath);
      StoragePath target = storagePaths.resolveChild(parent, name);
      if (!Files.isDirectory(parent.path())) {
        throw new NotFoundException("Folder not found: " + parent.key());
      }
      if (Files.exists(target.path(), LinkOption.NOFOLLOW_LINKS)) {
        throw new ConflictException("\"" + name + "\" already exists here");
      }

      inTransaction(
          () -> {
            FileMetadata folder = claim(target, true, 0, user);
            Files.createDirectory(target.path());
            history.recordSuccess(folder, ChangeType.CREATE_FOLDER, user, null);
          });
    } catch (IOException | RuntimeException e) {
      String attempted =
          parentPath == null || parentPath.isEmpty() ? name : parentPath + "/" + name;
      history.recordFailure(attempted, name, ChangeType.CREATE_FOLDER, user, e);
      throw e;
    }
  }

  /**
   * Brings back a previous version of a file, either over the live file (which is kept as a new
   * version first) or as a copy next to it.
   */
  public void restoreVersion(Long fileId, int number, RestoreMode mode) throws IOException {
    User user = authService.currentUser();
    FileMetadata file =
        files.findById(fileId).orElseThrow(() -> new NotFoundException("File not found"));
    if (Boolean.TRUE.equals(file.getIsDirectory())) {
      throw new BadRequestException("Folders do not have versions");
    }

    try {
      inTransaction(
          () -> {
            FileMetadata current = files.findById(fileId).orElseThrow();
            Path source = versions.storedCopy(current, number);
            if (mode == RestoreMode.COPY) {
              restoreAsCopy(current, source, number, user);
            } else {
              restoreInPlace(current, source, number, user);
            }
          });
    } catch (IOException | RuntimeException e) {
      history.recordFailure(file.getPath(), file.getFilename(), ChangeType.RESTORE, user, e);
      throw e;
    }
  }

  private void restoreInPlace(FileMetadata file, Path source, int number, User user)
      throws IOException {
    StoragePath live = storagePaths.resolveItem(file.getPath());
    Files.createDirectories(live.path().getParent());
    Path scratch = StorageFiles.tempFileBeside(live.path());
    try {
      Files.copy(source, scratch, StandardCopyOption.REPLACE_EXISTING);
      if (Files.exists(live.path())) {
        versions.archive(file, live.path(), user);
      }
      StorageFiles.moveIntoPlace(scratch, live.path());
    } finally {
      Files.deleteIfExists(scratch);
    }

    file.setSize(Files.size(live.path()));
    file.setUpdatedAt(Instant.now());
    history.recordSuccess(file, ChangeType.RESTORE, user, "Restored version " + number);
  }

  private void restoreAsCopy(FileMetadata file, Path source, int number, User user)
      throws IOException {
    StoragePath parent = storagePaths.resolve(parentKey(file.getPath()));
    StoragePath target = freeCopyName(parent, file.getFilename(), number);
    Files.createDirectories(parent.path());
    Files.copy(source, target.path());

    FileMetadata copy = claim(target, false, Files.size(target.path()), user);
    history.recordSuccess(
        copy,
        ChangeType.RESTORE,
        user,
        "Restored a copy of " + file.getPath() + " (version " + number + ")");
  }

  // "report_v2.txt", or "report_v2 (2).txt" if that is taken, so a restore never overwrites.
  private StoragePath freeCopyName(StoragePath parent, String filename, int number) {
    int dot = filename.lastIndexOf('.');
    String base = dot > 0 ? filename.substring(0, dot) : filename;
    String extension = dot > 0 ? filename.substring(dot) : "";

    for (int attempt = 1; attempt <= MAX_COPY_NAME_ATTEMPTS; attempt++) {
      String suffix = attempt == 1 ? "" : " (" + attempt + ")";
      StoragePath candidate =
          storagePaths.resolveChild(parent, base + "_v" + number + suffix + extension);
      if (!Files.exists(candidate.path(), LinkOption.NOFOLLOW_LINKS)) {
        return candidate;
      }
    }
    throw new ConflictException("Could not find a free name for the restored copy");
  }

  /**
   * The downloadable content at a path: the file itself, or a folder to be zipped.
   *
   * @throws NotFoundException if nothing is there
   */
  public Download download(String path) throws IOException {
    StoragePath target = storagePaths.resolveItem(path);
    if (!Files.exists(target.path())) {
      throw new NotFoundException("Not found: " + target.key());
    }
    files.markAccessed(target.key(), Instant.now());

    if (Files.isDirectory(target.path())) {
      return new Download.FolderDownload(target.path(), target.name() + ".zip");
    }
    String contentType = Files.probeContentType(target.path());
    return new Download.FileDownload(
        target.path(),
        target.name(),
        contentType != null ? contentType : "application/octet-stream");
  }

  /** Whether something exists at a path. Refuses the root, which can never be shared. */
  public boolean exists(String path) {
    return Files.exists(storagePaths.resolveItem(path).path());
  }

  /**
   * The metadata row for a new item at {@code target}. A row can outlive its file, e.g. when the
   * file was removed outside the app; it is reset rather than duplicated, and its versions (which
   * belong to the old file) are dropped.
   */
  private FileMetadata claim(StoragePath target, boolean isDirectory, long size, User owner) {
    Optional<FileMetadata> leftover = files.findByPath(target.key());
    if (leftover.isPresent()) {
      FileMetadata row = leftover.get();
      versions.discardAll(row);
      row.startOver(size, isDirectory, owner);
      return files.save(row);
    }
    return files.save(new FileMetadata(target.key(), target.name(), size, isDirectory, owner));
  }

  // A file that is on disk but was never tracked, e.g. copied in by hand, gets a row now so its
  // current content can be kept as a version before it is replaced.
  private FileMetadata existingOrNew(StoragePath target, long size, User user) {
    return files
        .findByPath(target.key())
        .orElseGet(
            () -> files.save(new FileMetadata(target.key(), target.name(), size, false, user)));
  }

  private static String childKey(StoragePath folder, String name) {
    return folder.isRoot() ? name : folder.key() + "/" + name;
  }

  private static String parentKey(String key) {
    int slash = key.lastIndexOf('/');
    return slash < 0 ? "" : key.substring(0, slash);
  }

  private static String lastSegment(String path) {
    if (path == null) {
      return null;
    }
    String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    return trimmed.substring(trimmed.lastIndexOf('/') + 1);
  }

  private interface IoWork {
    void run() throws IOException;
  }

  private void inTransaction(IoWork work) throws IOException {
    try {
      transactions.executeWithoutResult(
          status -> {
            try {
              work.run();
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }
}
