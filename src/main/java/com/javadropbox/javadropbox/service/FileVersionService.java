package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.FileVersionDto;
import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.FileVersion;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.FileVersionRepository;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps previous versions of files. Each version is stored as {@code .versions/<file id>/v<n>} in
 * the {@link FileStore}, so two files that share a name in different folders never share storage.
 * Rows written before this layout store a bare {@code <name>.v<n>}; both resolve relative to the
 * versions folder.
 */
@Service
public class FileVersionService {

  private static final Logger log = LoggerFactory.getLogger(FileVersionService.class);

  private final FileVersionRepository versions;
  private final FileMetadataRepository files;
  private final FileStore store;
  private final int maxRetained;

  public FileVersionService(
      FileVersionRepository versions,
      FileMetadataRepository files,
      FileStore store,
      @Value("${javadropbox.versions.max-retained:10}") int maxRetained) {
    // Checked here so a bad setting stops the app starting, rather than failing every replace.
    if (maxRetained < 0) {
      throw new IllegalArgumentException(
          "javadropbox.versions.max-retained must be 0 or more, not " + maxRetained);
    }
    this.versions = versions;
    this.files = files;
    this.store = store;
    this.maxRetained = maxRetained;
  }

  /**
   * The stored versions of one of {@code owner}'s files, newest first.
   *
   * @throws NotFoundException if there is no such file, or someone else owns it
   */
  @Transactional(readOnly = true)
  public List<FileVersionDto> list(Long fileId, User owner) {
    FileMetadata file =
        files
            .findOwned(fileId, owner.getId())
            .orElseThrow(() -> new NotFoundException("File not found"));
    return versions.findByFileMetadataOrderByVersionDesc(file).stream()
        .map(FileVersionDto::fromEntity)
        .toList();
  }

  /**
   * Moves the live file, at {@code live} in the store, into the version store as its next version,
   * then prunes versions beyond the retention limit. Runs in the caller's transaction, which must
   * hold the lock on the file's row ({@link FileMetadataRepository#lockById}) so that no one else
   * numbers a version meanwhile.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void archive(FileMetadata file, String live, User user) throws IOException {
    int number = file.getCurrentVersion() != null ? file.getCurrentVersion() : 1;
    String stored = file.getId() + "/v" + number;
    String target = resolve(stored);

    store.createFolders(StoragePaths.VERSIONS_DIR + "/" + file.getId());
    if (store.exists(target)) {
      removeLeftover(target, stored);
    }
    // Never replacing: a file that is in the way now belongs to someone else, and failing is
    // better than overwriting it.
    store.move(live, target);
    // A fresh modification time tells the startup sweep that this is no leftover even before the
    // row below is committed.
    store.touch(target);
    // Also covers the caller having moved new content onto the live path since: the previous
    // content goes back over it.
    OnRollback.undo("archiving " + live + " as " + target, () -> store.replace(target, live));
    long size = store.stat(target).orElseThrow(() -> new NoSuchFileException(target)).size();
    versions.save(new FileVersion(file, number, stored, size, user));
    file.setCurrentVersion(number + 1);

    List<FileVersion> all = versions.findByFileMetadataOrderByVersionDesc(file);
    for (FileVersion old : all.subList(Math.min(maxRetained, all.size()), all.size())) {
      delete(old);
    }
  }

  // Under the row lock nobody else can be writing this version, so a file already there that no
  // row points to is left over from an operation that was interrupted before it could clean up.
  private void removeLeftover(String target, String stored) throws IOException {
    if (versions.existsByStoredFilename(stored)) {
      throw new IllegalStateException("Version " + stored + " already exists");
    }
    log.warn("Removing {}, left over from an interrupted operation", target);
    store.deleteRecursively(target);
  }

  /**
   * The key in the store of one version's stored copy.
   *
   * @throws NotFoundException if the version does not exist or its file is missing
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public String storedCopy(FileMetadata file, int number) throws IOException {
    FileVersion version =
        versions.findByFileMetadataOrderByVersionDesc(file).stream()
            .filter(v -> v.getVersion() == number)
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Version " + number + " not found"));
    String stored = resolve(version.getStoredFilename());
    if (store.stat(stored).map(FileStore.Entry::isDirectory).orElse(true)) {
      throw new NotFoundException("The stored copy of version " + number + " is missing");
    }
    return stored;
  }

  /**
   * Deletes every version of a file, e.g. because the file is being deleted or a new item is taking
   * over its path. The rows go now; the stored files once that commits.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void discardAll(FileMetadata file) {
    versions.findByFileMetadataOrderByVersionDesc(file).forEach(this::delete);
    String folder = StoragePaths.VERSIONS_DIR + "/" + file.getId();
    // Along with anything left in it, e.g. from an interrupted operation.
    AfterCommit.run("remove " + folder, () -> store.deleteRecursively(folder));
  }

  /**
   * Deletes the versions of the item at {@code path} in an account's folder and of everything below
   * it, e.g. because a folder is being deleted with all it holds. Takes the same few statements
   * however many items there are. The rows go now; the stored files once that commits.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void discardAllAtOrBelow(Long ownerId, String path) {
    String below = FileMetadataRepository.below(path);
    List<String> stored =
        versions.findStoredFilenamesAtOrBelow(ownerId, path, below).stream()
            .map(FileVersionService::resolve)
            .toList();
    List<String> folders =
        files.findIdsAtOrBelow(ownerId, path, below).stream()
            .map(id -> StoragePaths.VERSIONS_DIR + "/" + id)
            .toList();
    versions.deleteAtOrBelow(ownerId, path, below);

    AfterCommit.run(
        "remove the versions of " + path,
        () -> {
          for (String file : stored) {
            store.delete(file);
          }
          for (String folder : folders) {
            store.deleteRecursively(folder);
          }
        });
  }

  private void delete(FileVersion version) {
    versions.delete(version);
    String stored = resolve(version.getStoredFilename());
    AfterCommit.run("remove " + stored, () -> store.delete(stored));
  }

  // Rows are written by the app, but a row pointing anywhere else would be the store's to lose.
  private static String resolve(String storedFilename) {
    for (String segment : storedFilename.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalStateException("Version row points outside the version store");
      }
    }
    return StoragePaths.VERSIONS_DIR + "/" + storedFilename;
  }
}
