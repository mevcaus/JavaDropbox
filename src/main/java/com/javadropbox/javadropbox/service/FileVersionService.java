package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.FileVersionDto;
import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.FileVersion;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.FileVersionRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps previous versions of files. Each version is stored as {@code .versions/<file id>/v<n>}, so
 * two files that share a name in different folders never share storage. Rows written before this
 * layout store a bare {@code <name>.v<n>}; both resolve relative to the versions directory.
 */
@Service
public class FileVersionService {

  private static final Logger log = LoggerFactory.getLogger(FileVersionService.class);

  private final FileVersionRepository versions;
  private final FileMetadataRepository files;
  private final StoragePaths storagePaths;
  private final int maxRetained;

  public FileVersionService(
      FileVersionRepository versions,
      FileMetadataRepository files,
      StoragePaths storagePaths,
      @Value("${javadropbox.versions.max-retained:10}") int maxRetained) {
    // Checked here so a bad setting stops the app starting, rather than failing every replace.
    if (maxRetained < 0) {
      throw new IllegalArgumentException(
          "javadropbox.versions.max-retained must be 0 or more, not " + maxRetained);
    }
    this.versions = versions;
    this.files = files;
    this.storagePaths = storagePaths;
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
   * Moves the live file into the version store as its next version, then prunes versions beyond the
   * retention limit. Runs in the caller's transaction, which must hold the lock on the file's row
   * ({@link FileMetadataRepository#lockById}) so that no one else numbers a version meanwhile.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void archive(FileMetadata file, Path live, User user) throws IOException {
    int number = file.getCurrentVersion() != null ? file.getCurrentVersion() : 1;
    String stored = file.getId() + "/v" + number;
    Path target = storagePaths.versionsDir().resolve(stored);

    Files.createDirectories(target.getParent());
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      removeLeftover(target, stored);
    }
    // Never REPLACE_EXISTING: a file that is in the way now belongs to someone else, and failing
    // is better than overwriting it.
    Files.move(live, target);
    // A move keeps the old modification time; a fresh one tells the startup sweep that this is no
    // leftover even before the row below is committed.
    Files.setLastModifiedTime(target, FileTime.from(Instant.now()));
    // Also covers the caller having moved new content onto the live path since: the previous
    // content goes back over it.
    OnRollback.undo(
        "archiving " + live + " as " + target, () -> StorageFiles.moveIntoPlace(target, live));
    versions.save(new FileVersion(file, number, stored, Files.size(target), user));
    file.setCurrentVersion(number + 1);

    List<FileVersion> all = versions.findByFileMetadataOrderByVersionDesc(file);
    for (FileVersion old : all.subList(Math.min(maxRetained, all.size()), all.size())) {
      delete(old);
    }
  }

  // Under the row lock nobody else can be writing this version, so a file already there that no
  // row points to is left over from an operation that was interrupted before it could clean up.
  private void removeLeftover(Path target, String stored) throws IOException {
    if (versions.existsByStoredFilename(stored)) {
      throw new IllegalStateException("Version " + stored + " already exists");
    }
    log.warn("Removing {}, left over from an interrupted operation", target);
    Files.delete(target);
  }

  /**
   * The stored copy of one version.
   *
   * @throws NotFoundException if the version does not exist or its file is missing
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Path storedCopy(FileMetadata file, int number) {
    FileVersion version =
        versions.findByFileMetadataOrderByVersionDesc(file).stream()
            .filter(v -> v.getVersion() == number)
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Version " + number + " not found"));
    Path stored = resolve(version.getStoredFilename());
    if (!Files.isRegularFile(stored)) {
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
    Path folder = storagePaths.versionsDir().resolve(String.valueOf(file.getId()));
    // With anything left in it, e.g. from an interrupted operation, deleteIfExists would fail.
    AfterCommit.run("remove " + folder, () -> StorageFiles.deleteRecursively(folder));
  }

  /**
   * Deletes the versions of the item at {@code path} in an account's folder and of everything below
   * it, e.g. because a folder is being deleted with all it holds. Takes the same few statements
   * however many items there are. The rows go now; the stored files once that commits.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void discardAllAtOrBelow(Long ownerId, String path) {
    String below = FileMetadataRepository.below(path);
    List<Path> stored =
        versions.findStoredFilenamesAtOrBelow(ownerId, path, below).stream()
            .map(this::resolve)
            .toList();
    List<Path> folders =
        files.findIdsAtOrBelow(ownerId, path, below).stream()
            .map(id -> storagePaths.versionsDir().resolve(String.valueOf(id)))
            .toList();
    versions.deleteAtOrBelow(ownerId, path, below);

    AfterCommit.run(
        "remove the versions of " + path,
        () -> {
          for (Path file : stored) {
            Files.deleteIfExists(file);
          }
          for (Path folder : folders) {
            StorageFiles.deleteRecursively(folder);
          }
        });
  }

  private void delete(FileVersion version) {
    versions.delete(version);
    Path stored = resolve(version.getStoredFilename());
    AfterCommit.run("remove " + stored, () -> Files.deleteIfExists(stored));
  }

  private Path resolve(String storedFilename) {
    Path versionsDir = storagePaths.versionsDir();
    Path stored = versionsDir.resolve(storedFilename).normalize();
    if (!stored.startsWith(versionsDir)) {
      throw new IllegalStateException("Version row points outside the version store");
    }
    return stored;
  }
}
