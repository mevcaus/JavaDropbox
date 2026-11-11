package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.FileVersionDto;
import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.FileVersion;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.FileVersionRepository;
import com.javadropbox.javadropbox.service.FileStore.Entry;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.time.Instant;
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
  private final FileContents contents;
  private final StorageQuota quota;
  private final int maxRetained;

  public FileVersionService(
      FileVersionRepository versions,
      FileMetadataRepository files,
      FileStore store,
      FileContents contents,
      StorageQuota quota,
      @Value("${javadropbox.versions.max-retained:10}") int maxRetained) {
    // Checked here so a bad setting stops the app starting, rather than failing every replace.
    if (maxRetained < 0) {
      throw new IllegalArgumentException(
          "javadropbox.versions.max-retained must be 0 or more, not " + maxRetained);
    }
    this.versions = versions;
    this.files = files;
    this.store = store;
    this.contents = contents;
    this.quota = quota;
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
   * Moves the live file, as the store has just shown it, into the version store as its next
   * version, then prunes versions beyond the retention limit. Runs in the caller's transaction,
   * which must hold the lock on the file's row ({@link FileMetadataRepository#lockById}) so that no
   * one else numbers a version meanwhile.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void archive(FileMetadata file, Entry live, User user) throws IOException {
    int number = file.getCurrentVersion() != null ? file.getCurrentVersion() : 1;
    String stored = file.getId() + "/v" + number;
    String target = FileContents.versionKey(stored);

    // Never replacing: a file that is in the way now belongs to someone else, and failing is
    // better than overwriting it, unless it is left over from an interrupted operation.
    try {
      store.move(live.key(), target);
    } catch (FileAlreadyExistsException e) {
      removeLeftover(target, stored);
      store.move(live.key(), target);
    }
    // A fresh modification time tells the startup sweep that this is no leftover even before the
    // row below is committed.
    store.touch(target);
    // Also covers the caller having moved new content onto the live path since: the previous
    // content goes back over it.
    OnRollback.undo(
        "archiving " + live.key() + " as " + target, () -> store.replace(target, live.key()));
    String hash =
        unchangedSince(live, file.getSize(), file.getUpdatedAt()) ? file.getContentHash() : null;
    versions.save(new FileVersion(file, number, stored, live.size(), hash, user));
    quota.add(file.getOwner().getId(), live.size());
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
   * One version's stored copy.
   *
   * @param key where it is in the store
   * @param contentHash its SHA-256, or null where it isn't known or the copy looks changed since
   */
  public record StoredCopy(String key, String contentHash) {}

  /**
   * One version's stored copy, which is there.
   *
   * @throws NotFoundException if the version does not exist or its file is missing
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public StoredCopy storedCopy(FileMetadata file, int number) throws IOException {
    FileVersion version =
        versions.findByFileMetadataOrderByVersionDesc(file).stream()
            .filter(v -> v.getVersion() == number)
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Version " + number + " not found"));
    Entry copy =
        contents
            .ofVersion(version)
            .filter(entry -> !entry.isDirectory())
            .orElseThrow(
                () ->
                    new NotFoundException("The stored copy of version " + number + " is missing"));
    String hash =
        unchangedSince(copy, version.getSize(), version.getCreatedAt())
            ? version.getContentHash()
            : null;
    return new StoredCopy(copy.key(), hash);
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
   * however many items there are. The caller holds the row locks of those items ({@code fileIds}),
   * so no version is added to them meanwhile. The rows go now; the stored files once that commits.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void discardAllAtOrBelow(Long ownerId, String path, List<Long> fileIds) {
    String below = FileMetadataRepository.below(path);
    List<String> stored =
        versions.findStoredFilenamesAtOrBelow(ownerId, path, below).stream()
            .map(FileContents::versionKey)
            .toList();
    List<String> folders =
        fileIds.stream().map(id -> StoragePaths.VERSIONS_DIR + "/" + id).toList();
    quota.add(ownerId, -versions.sizeAtOrBelow(ownerId, path, below));
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
    quota.add(
        version.getFileMetadata().getOwner().getId(),
        version.getSize() == null ? 0 : -version.getSize());
    versions.delete(version);
    String stored = FileContents.versionKey(version.getStoredFilename());
    AfterCommit.run("remove " + stored, () -> store.delete(stored));
  }

  /**
   * Whether stored content still looks like what its row describes: the same size, and not changed
   * since the row was written. A hash recorded for it is only carried on when it does, since
   * content edited outside the app, even to the same length, has another hash. A store that doesn't
   * know when the content changed, or whose clock is ahead of the app's, gives no hint.
   *
   * <p>Neither check is proof, so such a hash is a hint, which has to be checked against the
   * content before anything is keyed by it. Edits it misses: a copy that keeps its old modification
   * time; one within the store's precision of the app's write (a few milliseconds on a disk, a
   * second in S3); any while the store's clock is behind the app's; and a release from before V13
   * changing content without clearing the hash.
   */
  static boolean unchangedSince(Entry content, Long recordedSize, Instant recordedAt) {
    return recordedSize != null
        && recordedSize == content.size()
        && content.modified() != null
        && recordedAt != null
        && !content.modified().isAfter(recordedAt);
  }
}
