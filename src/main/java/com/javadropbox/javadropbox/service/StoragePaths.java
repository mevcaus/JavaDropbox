package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.exception.BadRequestException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place a client-supplied path is turned into a filesystem path. Every storage operation
 * resolves its paths through here, so the rules live in one place:
 *
 * <ul>
 *   <li>the result stays inside the serving directory after {@code ..} is normalized away;
 *   <li>it stays inside it after symlinks are followed, so a link cannot expose the rest of the
 *       disk;
 *   <li>it never reaches the directories the app keeps its own data in ({@link #RESERVED_DIRS}).
 * </ul>
 */
@Component
public class StoragePaths {

  /** Where previous versions of files are kept. */
  public static final String VERSIONS_DIR = ".versions";

  /** Where the app keeps its own state, such as the generated share-link key. */
  public static final String INTERNAL_DIR = ".javadropbox";

  private static final Set<String> RESERVED_DIRS = Set.of(VERSIONS_DIR, INTERNAL_DIR);
  private static final String INVALID_PATH = "Invalid path";

  /** The longest file or folder name most filesystems accept, and the width of the column. */
  static final int MAX_NAME_LENGTH = 255;

  private static final Logger log = LoggerFactory.getLogger(StoragePaths.class);

  private final Path root;
  private final Path realRoot;

  public StoragePaths(
      @Value("${javadropbox.serving.directory:#{systemProperties['user.dir']}}") String directory)
      throws IOException {
    this.root = Path.of(directory).toAbsolutePath().normalize();
    Files.createDirectories(root);
    this.realRoot = root.toRealPath();
  }

  public Path root() {
    return root;
  }

  public Path versionsDir() {
    return root.resolve(VERSIONS_DIR);
  }

  public Path internalDir() {
    return root.resolve(INTERNAL_DIR);
  }

  /**
   * Resolves a path relative to the serving directory. {@code null} and the empty string mean the
   * root itself.
   *
   * @throws BadRequestException if the path escapes the serving directory or names a reserved one
   */
  public StoragePath resolve(String relativePath) {
    String raw = relativePath == null ? "" : relativePath;
    Path candidate;
    try {
      candidate = root.resolve(raw).normalize();
    } catch (InvalidPathException e) {
      throw new BadRequestException(INVALID_PATH);
    }

    if (!candidate.startsWith(root)) {
      log.warn("Rejected path outside the serving directory: {}", raw);
      throw new BadRequestException(INVALID_PATH);
    }

    Path relative = root.relativize(candidate);
    if (relative.getNameCount() > 0 && RESERVED_DIRS.contains(relative.getName(0).toString())) {
      throw new BadRequestException(INVALID_PATH);
    }

    ensureInsideRealRoot(candidate, raw);
    return new StoragePath(candidate, toKey(relative));
  }

  /**
   * Like {@link #resolve} but refuses the root itself, for operations that act on one item --
   * deleting or sharing "everything" is never what a request meant.
   */
  public StoragePath resolveItem(String relativePath) {
    StoragePath path = resolve(relativePath);
    if (path.isRoot()) {
      throw new BadRequestException("This operation is not allowed on the root folder");
    }
    return path;
  }

  /**
   * Resolves a single new entry inside {@code parent}, such as an uploaded file or a folder being
   * created. The name must be one path segment.
   */
  public StoragePath resolveChild(StoragePath parent, String name) {
    if (name == null
        || name.isBlank()
        || name.equals(".")
        || name.equals("..")
        || name.contains("/")
        || name.contains("\\")
        || name.indexOf('\0') >= 0) {
      throw new BadRequestException("Invalid name: " + name);
    }
    if (name.length() > MAX_NAME_LENGTH) {
      throw new BadRequestException("Names can be at most " + MAX_NAME_LENGTH + " characters");
    }
    return resolveItem(parent.isRoot() ? name : parent.key() + "/" + name);
  }

  /** The storage key (forward-slash relative path) for a path already known to be inside root. */
  public String keyOf(Path path) {
    return toKey(root.relativize(path.toAbsolutePath().normalize()));
  }

  // Normalizing only removes "..": a symlink inside the serving directory can still point
  // anywhere, so check where the deepest existing part of the path really leads.
  private void ensureInsideRealRoot(Path candidate, String raw) {
    Path existing = candidate;
    while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      existing = existing.getParent();
    }
    if (existing == null) {
      throw new BadRequestException(INVALID_PATH);
    }
    try {
      if (!existing.toRealPath().startsWith(realRoot)) {
        log.warn("Rejected path that leaves the serving directory through a symlink: {}", raw);
        throw new BadRequestException(INVALID_PATH);
      }
    } catch (IOException e) {
      // A dangling symlink, or one that loops.
      throw new BadRequestException(INVALID_PATH);
    }
  }

  private static String toKey(Path relative) {
    return relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
  }

  /**
   * A validated location inside the serving directory.
   *
   * @param path the absolute filesystem path
   * @param key the path relative to the serving directory with forward slashes, {@code ""} for the
   *     root. This is the form stored in the database and shown to clients.
   */
  public record StoragePath(Path path, String key) {

    public boolean isRoot() {
      return key.isEmpty();
    }

    public String name() {
      return path.getFileName().toString();
    }
  }
}
