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
    this.versions = versions;
    this.files = files;
    this.storagePaths = storagePaths;
    this.maxRetained = maxRetained;
  }

  /** The stored versions of a file, newest first. */
  @Transactional(readOnly = true)
  public List<FileVersionDto> list(Long fileId) {
    FileMetadata file =
        files.findById(fileId).orElseThrow(() -> new NotFoundException("File not found"));
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
    Path stored = resolve(version);
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
    AfterCommit.run("remove " + folder, () -> Files.deleteIfExists(folder));
  }

  private void delete(FileVersion version) {
    versions.delete(version);
    Path stored = resolve(version);
    AfterCommit.run("remove " + stored, () -> Files.deleteIfExists(stored));
  }

  private Path resolve(FileVersion version) {
    Path versionsDir = storagePaths.versionsDir();
    Path stored = versionsDir.resolve(version.getStoredFilename()).normalize();
    if (!stored.startsWith(versionsDir)) {
      throw new IllegalStateException("Version row points outside the version store");
    }
    return stored;
  }
}
