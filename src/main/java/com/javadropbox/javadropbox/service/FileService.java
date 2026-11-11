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
import com.javadropbox.javadropbox.service.FileStore.Entry;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import com.javadropbox.javadropbox.service.StoragePaths.StoragePath;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.InputStreamSource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Changes to stored files and folders, each in the folder of the account making it, in the {@link
 * FileStore}. Each change runs in one database transaction, and a failure part-way leaves the
 * previous content in place: new content is written to a scratch file outside the transaction, and
 * the moves inside it -- the live file into the version store, the scratch file onto the live path
 * -- are undone if the transaction does not commit.
 *
 * <p>Failures are logged to the history in a transaction of their own after the change's
 * transaction has rolled back. Each change also tells the {@link SearchIndex}, which reads the
 * result from disk once the transaction has finished.
 */
@Service
public class FileService {

  private static final int MAX_COPY_NAME_ATTEMPTS = 1000;
  private static final String OCTET_STREAM = "application/octet-stream";

  private final StoragePaths storagePaths;
  private final FileStore store;
  private final FileMetadataRepository files;
  private final FileVersionService versions;
  private final FileHistoryService history;
  private final ShareLinkRepository shareLinks;
  private final AuthService authService;
  private final UsageMetrics metrics;
  private final StorageQuota quota;
  private final SearchIndex searchIndex;
  private final TransactionTemplate transactions;

  public FileService(
      StoragePaths storagePaths,
      FileStore store,
      FileMetadataRepository files,
      FileVersionService versions,
      FileHistoryService history,
      ShareLinkRepository shareLinks,
      AuthService authService,
      UsageMetrics metrics,
      StorageQuota quota,
      SearchIndex searchIndex,
      PlatformTransactionManager transactionManager) {
    this.storagePaths = storagePaths;
    this.store = store;
    this.files = files;
    this.versions = versions;
    this.history = history;
    this.shareLinks = shareLinks;
    this.authService = authService;
    this.metrics = metrics;
    this.quota = quota;
    this.searchIndex = searchIndex;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Stores uploaded files in a folder. Each file is committed on its own, so a failure on one does
   * not undo the ones before it; the failing file and everything after it are not stored. Folders
   * the upload makes on the way are committed on their own first, and stay if a file then fails, as
   * a folder made with {@link #createFolder} would.
   *
   * @return how many files were stored
   */
  public int upload(MultipartFile[] uploads, String folderPath) throws IOException {
    User user = authService.requireCurrentUser();
    Home home = storagePaths.home(user);
    StoragePath folder;
    try {
      folder = home.resolve(folderPath);
      createFolders(folder, user);
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
        store(upload, home.resolveChild(folder, name), user);
      } catch (IOException | RuntimeException e) {
        history.recordFailure(childKey(folder, name), name, ChangeType.UPLOAD, user, e);
        throw e;
      }
      stored++;
    }
    return stored;
  }

  /**
   * Stores a file the server supplies itself, such as one of the demo's sample files, as if {@code
   * user} had uploaded it to {@code folderPath} in their folder (which is created if need be).
   */
  public void store(String folderPath, String name, InputStreamSource content, User user)
      throws IOException {
    Home home = storagePaths.home(user);
    StoragePath folder = home.resolve(folderPath);
    createFolders(folder, user);
    store(content, home.resolveChild(folder, name), user);
  }

  /**
   * Creates {@code folder} and whatever folders above it are missing, in a transaction of its own.
   * Does nothing, and opens no transaction, when the folder is already there.
   */
  private void createFolders(StoragePath folder, User user) throws IOException {
    List<String> missing = missingFolders(folder);
    if (!missing.isEmpty()) {
      inTransaction(() -> createFolders(folder.home(), missing, user));
    }
  }

  /**
   * The keys of {@code folder} and of the folders above it that the store doesn't have, topmost
   * first.
   *
   * @throws BadRequestException where a segment of the path is a file, rather than the error the
   *     store would give, which says nothing useful to the client
   */
  private List<String> missingFolders(StoragePath folder) throws IOException {
    List<String> missing = new ArrayList<>();
    String key = folder.key();
    Optional<Entry> existing;
    while ((existing = store.stat(storeKey(folder.home(), key))).isEmpty() && !key.isEmpty()) {
      missing.add(0, key);
      key = parentKey(key);
    }
    if (existing.isPresent() && !existing.get().isDirectory()) {
      throw new BadRequestException("\"" + key + "\" is a file, not a folder");
    }
    return missing;
  }

  /**
   * Creates the {@code missing} folders, topmost first, each with a row and a history entry as if
   * the user had created it, so it has an id and an owner like anything else. Must run in a
   * transaction; the folders are removed again if it rolls back, unless something was put in them.
   */
  private void createFolders(Home home, List<String> missing, User user) throws IOException {
    if (missing.isEmpty()) {
      return;
    }
    List<String> created = new ArrayList<>();
    for (String key : missing) {
      boolean inserted =
          files.insertFolderIfAbsent(user.getId(), key, lastSegment(key), Instant.now()) == 1;
      // Ours, or the one already there: another request creating the same folder, which this
      // waited for, or a row that outlived its folder because it was removed outside the app.
      Optional<FileMetadata> row = files.lockByPath(user.getId(), key);
      Optional<Entry> stored = store.stat(storeKey(home, key));
      if (stored.isPresent()) {
        if (!stored.get().isDirectory()) {
          throw new BadRequestException("\"" + key + "\" is a file, not a folder");
        }
        // Made by another request, or outside the app, since this one looked. A row that was
        // added here is kept, so the folder has one, but this request didn't create the folder.
        // Should the other request roll back and remove it again, this row is left without its
        // folder, and the next request that needs the folder starts the row over and makes it.
        continue;
      }
      if (row.isEmpty()) {
        // Deleted while this waited for it.
        throw new ConflictException("\"" + key + "\" changed while it was being created");
      }
      FileMetadata folder = inserted ? row.get() : startOver(row.get(), true, 0);
      history.recordSuccess(folder, ChangeType.CREATE_FOLDER, user, null);
      created.add(key);
    }
    if (created.isEmpty()) {
      return;
    }

    List<String> undo = new ArrayList<>(created);
    Collections.reverse(undo);
    OnRollback.undo(
        "creating " + storeKey(home, created.get(0)),
        () -> {
          // Deepest first. A folder something was put in meanwhile is kept, and so is every
          // folder above it.
          for (String key : undo) {
            try {
              store.delete(storeKey(home, key));
            } catch (DirectoryNotEmptyException e) {
              return;
            }
          }
        });
    store.createFolders(storeKey(home, created.get(created.size() - 1)));
    // The file going into them is reported on its own, once it is stored.
    searchIndex.changed(home, created.get(0));
  }

  private void store(InputStreamSource upload, StoragePath target, User user) throws IOException {
    if (isFolder(target)) {
      throw new ConflictException("A folder named \"" + target.name() + "\" already exists here");
    }

    String scratch = store.scratchBeside(target.storeKey());
    try {
      long size;
      try (InputStream in = upload.getInputStream()) {
        size = store.write(scratch, in, lengthOf(upload));
      }
      quota.check(user);

      inTransaction(
          () -> {
            // Locked before looking at the store, so that concurrent uploads of one file take
            // turns and each archives what the one before it left.
            Optional<FileMetadata> row = files.lockByPath(user.getId(), target.key());
            Optional<Entry> current = store.stat(target.storeKey());
            if (row.isEmpty() && current.isPresent()) {
              // A file with no row: given one, waiting for an import or another upload giving it
              // one, and looked at again under that row's lock, since the other may have changed
              // it meanwhile.
              row = Optional.of(track(target, size, user));
              current = store.stat(target.storeKey());
            }
            boolean replacing = current.isPresent();
            FileMetadata file = replacing ? row.get() : claim(target, row, false, size, user);
            if (replacing) {
              versions.archive(file, current.get(), user);
            }
            moveIntoPlace(scratch, target.storeKey(), replacing);

            file.setSize(size);
            file.setUpdatedAt(Instant.now());
            history.recordSuccess(file, ChangeType.UPLOAD, user, null);
            searchIndex.changed(target);
          });
      metrics.fileUploaded(size);
    } finally {
      store.delete(scratch);
    }
  }

  // Known for an upload and for a file the server supplies, so a store elsewhere can send the
  // content on as it arrives rather than keep it first.
  private static long lengthOf(InputStreamSource source) {
    try {
      if (source instanceof MultipartFile upload) {
        return upload.getSize();
      }
      if (source instanceof Resource resource) {
        return resource.contentLength();
      }
    } catch (IOException e) {
      // Not known after all.
    }
    return -1;
  }

  /** Deletes a file, or a folder with everything in it, along with their metadata and versions. */
  public void delete(String path) throws IOException {
    User user = authService.requireCurrentUser();
    try {
      StoragePath target = storagePaths.home(user).resolveItem(path);

      inTransaction(
          () -> {
            // Bulk statements, so a folder costs the same few queries however much it holds. They
            // run before anything irreversible happens in the store, so a database problem stops
            // it.
            versions.discardAllAtOrBelow(user.getId(), target.key());
            int rows =
                files.deleteAtOrBelow(
                    user.getId(), target.key(), FileMetadataRepository.below(target.key()));

            // The list shows rows, so an item removed from the store behind the app's back is
            // still listed, and deleting it takes away just its rows. Nothing in either is what a
            // concurrent delete of the same item leaves: the deletes above waited for its row
            // locks and found nothing.
            boolean stored = store.exists(target.storeKey());
            if (!stored && rows == 0) {
              throw new NotFoundException("Not found: " + target.key());
            }
            if (stored) {
              try {
                store.deleteRecursively(target.storeKey());
              } catch (NoSuchFileException e) {
                if (rows == 0) {
                  throw new NotFoundException("Not found: " + target.key());
                }
              }
            }
            history.recordDeletion(target.key(), target.name(), user);
            searchIndex.changed(target);
          });
    } catch (IOException | RuntimeException e) {
      history.recordFailure(path, lastSegment(path), ChangeType.DELETE, user, e);
      throw e;
    }
  }

  public void createFolder(String parentPath, String name) throws IOException {
    User user = authService.requireCurrentUser();
    try {
      Home home = storagePaths.home(user);
      StoragePath parent = home.resolve(parentPath);
      StoragePath target = home.resolveChild(parent, name);
      if (!isFolder(parent)) {
        throw new NotFoundException("Folder not found: " + parent.key());
      }
      if (store.exists(target.storeKey())) {
        throw new ConflictException("\"" + name + "\" already exists here");
      }

      inTransaction(
          () -> {
            FileMetadata folder =
                claim(target, files.lockByPath(user.getId(), target.key()), true, 0, user);
            store.createFolder(target.storeKey());
            history.recordSuccess(folder, ChangeType.CREATE_FOLDER, user, null);
            searchIndex.changed(target);
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
    User user = authService.requireCurrentUser();
    // Another account's file is as good as missing.
    FileMetadata file =
        files
            .findOwned(fileId, user.getId())
            .orElseThrow(() -> new NotFoundException("File not found"));
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
            String source = versions.storedCopy(current, number);
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

  private void restoreInPlace(FileMetadata file, String source, int number, User user)
      throws IOException {
    Home home = storagePaths.home(user);
    StoragePath live = home.resolveItem(file.getPath());
    StoragePath parent = home.resolve(parentKey(live.key()));
    createFolders(home, missingFolders(parent), user);
    String scratch = store.scratchBeside(live.storeKey());
    try {
      store.copy(source, scratch);
      quota.check(user);
      Optional<Entry> current = store.stat(live.storeKey());
      boolean replacing = current.isPresent();
      if (replacing) {
        versions.archive(file, current.get(), user);
      }
      moveIntoPlace(scratch, live.storeKey(), replacing);
    } finally {
      store.delete(scratch);
    }

    file.setSize(sizeOf(live));
    file.setUpdatedAt(Instant.now());
    history.recordSuccess(file, ChangeType.RESTORE, user, "Restored version " + number);
    searchIndex.changed(live);
  }

  private void restoreAsCopy(FileMetadata file, String source, int number, User user)
      throws IOException {
    Home home = storagePaths.home(user);
    StoragePath parent = home.resolve(parentKey(file.getPath()));
    createFolders(home, missingFolders(parent), user);
    // Copied to a scratch file first and moved into place, like an upload, so the copy never
    // shows up under its name half-written.
    String scratch = store.scratchBeside(home.resolveItem(file.getPath()).storeKey());
    try {
      store.copy(source, scratch);
      quota.check(user);
      StoragePath target = claimCopyName(parent, file.getFilename(), number);
      OnRollback.undo("creating " + target.storeKey(), () -> store.delete(target.storeKey()));
      store.replace(scratch, target.storeKey());

      FileMetadata copy =
          claim(target, files.lockByPath(user.getId(), target.key()), false, sizeOf(target), user);
      history.recordSuccess(
          copy,
          ChangeType.RESTORE,
          user,
          "Restored a copy of " + file.getPath() + " (version " + number + ")");
      searchIndex.changed(target);
    } finally {
      store.delete(scratch);
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
          parent.home().resolveChild(parent, base + "_v" + number + suffix + extension);
      if (store.createFile(candidate.storeKey())) {
        return candidate;
      }
    }
    throw new ConflictException("Could not find a free name for the restored copy");
  }

  /**
   * Moves a scratch file onto {@code target}. What was there before has been archived (whose undo
   * puts it back); if nothing was, the new file is removed again should the transaction fail.
   */
  private void moveIntoPlace(String scratch, String target, boolean replacing) throws IOException {
    store.replace(scratch, target);
    if (!replacing) {
      OnRollback.undo("creating " + target, () -> store.delete(target));
    }
  }

  /**
   * The downloadable content at a path in the signed-in user's folder: the file itself, or a folder
   * to be zipped.
   *
   * @throws NotFoundException if nothing is there
   */
  public Download download(String path) throws IOException {
    return download(storagePaths.home(authService.requireCurrentUser()).resolveItem(path));
  }

  /** Like {@link #download(String)}, for an item already resolved, such as a share link's. */
  public Download download(StoragePath target) throws IOException {
    Entry item = find(target);

    if (item.isDirectory()) {
      String name = target.name();
      return new Download.FolderDownload(
          name + ".zip", out -> FolderArchive.write(store, target.storeKey(), name, out));
    }
    return new Download.FileDownload(
        store.resource(item), target.name(), contentType(target.name()));
  }

  // By the name's extension, as the system's own MIME types have it.
  private static String contentType(String name) {
    try {
      String type = Files.probeContentType(Path.of(name));
      return type != null ? type : OCTET_STREAM;
    } catch (IOException | InvalidPathException e) {
      return OCTET_STREAM;
    }
  }

  /**
   * A file in the signed-in user's folder to show in the browser. Only the kinds {@link
   * PreviewType} knows are offered; anything else has to be downloaded.
   *
   * @throws NotFoundException if nothing is there
   * @throws BadRequestException for a folder, or a file of a kind that cannot be previewed
   */
  public Preview preview(String path) throws IOException {
    return preview(storagePaths.home(authService.requireCurrentUser()).resolveItem(path));
  }

  /** Like {@link #preview(String)}, for an item already resolved, such as a share link's. */
  public Preview preview(StoragePath target) throws IOException {
    Entry item = find(target);
    if (item.isDirectory()) {
      throw new BadRequestException("Folders cannot be previewed: " + target.key());
    }
    PreviewType type =
        PreviewType.of(target.name())
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "This kind of file cannot be previewed: " + target.key()));
    return new Preview(
        store.resource(item), target.name(), type, PreviewType.contentType(target.name()));
  }

  private Entry find(StoragePath target) throws IOException {
    return store
        .stat(target.storeKey())
        .orElseThrow(() -> new NotFoundException("Not found: " + target.key()));
  }

  private boolean isFolder(StoragePath target) throws IOException {
    return store.stat(target.storeKey()).map(Entry::isDirectory).orElse(false);
  }

  private long sizeOf(StoragePath file) throws IOException {
    return store
        .stat(file.storeKey())
        .orElseThrow(() -> new NoSuchFileException(file.storeKey()))
        .size();
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
      return startOver(leftover.get(), isDirectory, size);
    }
    return files.save(new FileMetadata(target.key(), target.name(), size, isDirectory, owner));
  }

  // A leftover row made to describe a new item: the old one's versions and share links go.
  private FileMetadata startOver(FileMetadata row, boolean isDirectory, long size) {
    versions.discardAll(row);
    shareLinks.deleteByFile(row);
    row.startOver(size, isDirectory);
    return files.save(row);
  }

  // A file that is stored but was never tracked, e.g. copied in by hand, gets a row now so its
  // current content can be kept as a version before it is replaced. An import or another upload
  // adding the row at the same time is waited for, and that row is used.
  private FileMetadata track(StoragePath target, long size, User user) {
    Instant now = Instant.now();
    files.insertIfAbsent(user.getId(), target.key(), target.name(), size, false, now, now);
    return files
        .lockByPath(user.getId(), target.key())
        .orElseThrow(
            () -> new ConflictException("\"" + target.name() + "\" changed while it was replaced"));
  }

  private static String storeKey(Home home, String key) {
    return key.isEmpty() ? home.key() : home.key() + "/" + key;
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
