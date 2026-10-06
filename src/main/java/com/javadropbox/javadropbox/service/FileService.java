package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.Download;
import com.javadropbox.javadropbox.dto.Preview;
import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.exception.ConflictException;
import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.model.FileHistory.ChangeType;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.PreviewType;
import com.javadropbox.javadropbox.model.RestoreMode;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.ShareLinkRepository;
import com.javadropbox.javadropbox.service.StoragePaths.StoragePath;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Changes to stored files and folders. Each change runs in one database transaction, and a failure
 * part-way leaves the previous content in place: new content is written to a scratch file outside
 * the transaction, and the moves inside it -- the live file into the version store, the scratch
 * file onto the live path -- are undone if the transaction does not commit.
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
  private final ShareLinkRepository shareLinks;
  private final AuthService authService;
  private final UsageMetrics metrics;
  private final StorageQuota quota;
  private final TransactionTemplate transactions;

  public FileService(
      StoragePaths storagePaths,
      FileMetadataRepository files,
      FileVersionService versions,
      FileHistoryService history,
      ShareLinkRepository shareLinks,
      AuthService authService,
      UsageMetrics metrics,
      StorageQuota quota,
      PlatformTransactionManager transactionManager) {
    this.storagePaths = storagePaths;
    this.files = files;
    this.versions = versions;
    this.history = history;
    this.shareLinks = shareLinks;
    this.authService = authService;
    this.metrics = metrics;
    this.quota = quota;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Stores uploaded files in a folder. Each file is committed on its own, so a failure on one does
   * not undo the ones before it; the failing file and everything after it are not stored.
   *
   * @return how many files were stored
   */
  public int upload(MultipartFile[] uploads, String folderPath) throws IOException {
    User user = authService.currentUser();
    StoragePath folder;
    try {
      folder = storagePaths.resolve(folderPath);
      createFolders(folder);
    } catch (IOException | RuntimeException e) {
      // Recorded against the first file, the one that could not be stored, like any failed upload.
      Arrays.stream(uploads)
          .map(MultipartFile::getOriginalFilename)
          .filter(name -> name != null && !name.isEmpty())
          .findFirst()
          .ifPresent(
              name ->
                  history.recordFailure(join(folderPath, name), name, ChangeType.UPLOAD, user, e));
      throw e;
    }

    int stored = 0;
    for (MultipartFile upload : uploads) {
      String name = upload.getOriginalFilename();
      // A file input left empty sends a part with no file name. An empty file is still a file.
      if (name == null || name.isEmpty()) {
        continue;
      }
      try {
        store(upload, storagePaths.resolveChild(folder, name), user);
      } catch (IOException | RuntimeException e) {
        history.recordFailure(childKey(folder, name), name, ChangeType.UPLOAD, user, e);
        throw e;
      }
      stored++;
    }
    return stored;
  }

  // Where a segment of the path is a file, createDirectories fails with a disk error that says
  // nothing useful to the client; say what is wrong instead.
  private void createFolders(StoragePath folder) throws IOException {
    Path existing = folder.path();
    while (!Files.exists(existing)) {
      existing = existing.getParent();
    }
    if (!Files.isDirectory(existing)) {
      // existing is an ancestor of the folder, so its key is the folder's with segments dropped.
      String key = folder.key();
      for (int up = folder.path().getNameCount() - existing.getNameCount(); up > 0; up--) {
        key = parentKey(key);
      }
      throw new BadRequestException("\"" + key + "\" is a file, not a folder");
    }
    Files.createDirectories(folder.path());
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
      quota.check();

      inTransaction(
          () -> {
            // Locked before looking at the disk, so that concurrent uploads of one file take
            // turns and each archives what the one before it left.
            Optional<FileMetadata> row = files.lockByPath(target.key());
            boolean replacing = Files.exists(target.path());
            FileMetadata file =
                replacing
                    ? row.orElseGet(() -> track(target, size, user))
                    : claim(target, row, false, size, user);
            if (replacing) {
              versions.archive(file, StoragePaths.recheck(target.path()), user);
            }
            moveIntoPlace(scratch, target.path(), replacing);

            file.setSize(size);
            file.setUpdatedAt(Instant.now());
            history.recordSuccess(file, ChangeType.UPLOAD, user, null);
          });
      metrics.fileUploaded(size);
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
            // Bulk statements, so a folder costs the same few queries however much it holds. They
            // run before anything irreversible happens on disk, so a database problem stops it.
            versions.discardAllAtOrBelow(target.key());
            files.deleteAtOrBelow(target.key(), FileMetadataRepository.below(target.key()));

            // A concurrent delete of the same item got here first: the deletes above waited for
            // its row locks and found nothing, and now the disk has nothing either.
            if (!Files.exists(target.path(), LinkOption.NOFOLLOW_LINKS)) {
              throw new NotFoundException("Not found: " + target.key());
            }
            try {
              StorageFiles.deleteRecursively(target.path());
            } catch (NoSuchFileException e) {
              throw new NotFoundException("Not found: " + target.key());
            }
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
            FileMetadata folder = claim(target, files.lockByPath(target.key()), true, 0, user);
            Files.createDirectory(StoragePaths.recheck(target.path()));
            history.recordSuccess(folder, ChangeType.CREATE_FOLDER, user, null);
          });
    } catch (IOException | RuntimeException e) {
      history.recordFailure(join(parentPath, name), name, ChangeType.CREATE_FOLDER, user, e);
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
            // Locked so that a concurrent replace or restore of the same file waits for this one
            // instead of archiving under the same version number.
            FileMetadata current =
                files.lockById(fileId).orElseThrow(() -> new NotFoundException("File not found"));
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
      quota.check();
      boolean replacing = Files.exists(live.path());
      if (replacing) {
        versions.archive(file, StoragePaths.recheck(live.path()), user);
      }
      moveIntoPlace(scratch, live.path(), replacing);
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
    Files.createDirectories(parent.path());
    // Copied to a scratch file first and renamed into place, like an upload, so the copy never
    // shows up under its name half-written.
    Path scratch = StorageFiles.tempFileBeside(storagePaths.resolveItem(file.getPath()).path());
    try {
      Files.copy(source, scratch, StandardCopyOption.REPLACE_EXISTING);
      quota.check();
      StoragePath target = claimCopyName(parent, file.getFilename(), number);
      OnRollback.undo("creating " + target.path(), () -> Files.deleteIfExists(target.path()));
      StorageFiles.moveIntoPlace(scratch, target.path());

      FileMetadata copy =
          claim(target, files.lockByPath(target.key()), false, Files.size(target.path()), user);
      history.recordSuccess(
          copy,
          ChangeType.RESTORE,
          user,
          "Restored a copy of " + file.getPath() + " (version " + number + ")");
    } finally {
      Files.deleteIfExists(scratch);
    }
  }

  // "report_v2.txt", or "report_v2 (2).txt" if that is taken, so a restore never overwrites. The
  // name is claimed by creating an empty file, which fails if anything is there already, so two
  // requests can't both pick a name that was free when they looked. The copy replaces it at once.
  private StoragePath claimCopyName(StoragePath parent, String filename, int number)
      throws IOException {
    int dot = filename.lastIndexOf('.');
    String base = dot > 0 ? filename.substring(0, dot) : filename;
    String extension = dot > 0 ? filename.substring(dot) : "";

    for (int attempt = 1; attempt <= MAX_COPY_NAME_ATTEMPTS; attempt++) {
      String suffix = attempt == 1 ? "" : " (" + attempt + ")";
      StoragePath candidate =
          storagePaths.resolveChild(parent, base + "_v" + number + suffix + extension);
      try {
        Files.createFile(StoragePaths.recheck(candidate.path()));
        return candidate;
      } catch (FileAlreadyExistsException e) {
        // Taken; try the next one.
      }
    }
    throw new ConflictException("Could not find a free name for the restored copy");
  }

  /**
   * Moves a scratch file onto {@code target}. What was there before has been archived (whose undo
   * puts it back); if nothing was, the new file is removed again should the transaction fail.
   */
  private static void moveIntoPlace(Path scratch, Path target, boolean replacing)
      throws IOException {
    StorageFiles.moveIntoPlace(scratch, target);
    if (!replacing) {
      OnRollback.undo("creating " + target, () -> Files.deleteIfExists(target));
    }
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

  /**
   * A file to show in the browser. Only the kinds {@link PreviewType} knows are offered; anything
   * else has to be downloaded.
   *
   * @throws NotFoundException if nothing is there
   * @throws BadRequestException for a folder, or a file of a kind that cannot be previewed
   */
  public Preview preview(String path) throws IOException {
    StoragePath target = storagePaths.resolveItem(path);
    if (!Files.exists(target.path())) {
      throw new NotFoundException("Not found: " + target.key());
    }
    if (Files.isDirectory(target.path())) {
      throw new BadRequestException("Folders cannot be previewed: " + target.key());
    }
    PreviewType type =
        PreviewType.of(target.name())
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "This kind of file cannot be previewed: " + target.key()));
    files.markAccessed(target.key(), Instant.now());
    return new Preview(target.path(), target.name(), type, PreviewType.contentType(target.name()));
  }

  /** Whether something exists at a path. Refuses the root, which can never be shared. */
  public boolean exists(String path) {
    return Files.exists(storagePaths.resolveItem(path).path());
  }

  /**
   * The metadata row for a new item at {@code target}. A row can outlive its file, e.g. when the
   * file was removed outside the app; that {@code leftover} is reset rather than duplicated, and
   * its versions and share links (which belong to the old file) are dropped.
   */
  private FileMetadata claim(
      StoragePath target,
      Optional<FileMetadata> leftover,
      boolean isDirectory,
      long size,
      User owner) {
    if (leftover.isPresent()) {
      FileMetadata row = leftover.get();
      versions.discardAll(row);
      shareLinks.deleteByFile(row);
      row.startOver(size, isDirectory, owner);
      return files.save(row);
    }
    return files.save(new FileMetadata(target.key(), target.name(), size, isDirectory, owner));
  }

  // A file that is on disk but was never tracked, e.g. copied in by hand, gets a row now so its
  // current content can be kept as a version before it is replaced.
  private FileMetadata track(StoragePath target, long size, User user) {
    return files.save(new FileMetadata(target.key(), target.name(), size, false, user));
  }

  private static String childKey(StoragePath folder, String name) {
    return folder.isRoot() ? name : folder.key() + "/" + name;
  }

  // The path a request meant, for the history of a request that failed before it was resolved.
  private static String join(String parentPath, String name) {
    return parentPath == null || parentPath.isEmpty() ? name : parentPath + "/" + name;
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
