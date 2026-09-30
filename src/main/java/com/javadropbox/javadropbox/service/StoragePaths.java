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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place a client-supplied path is turned into a filesystem path. Every storage operation
 * resolves its paths through here, so the rules live in one place:
 *
 * <ul>
 *   <li>the result stays inside the serving directory after {@code ..} is normalized away;
 *   <li>it does not pass through a symlink: the file tree never shows one, and one could lead
 *       outside the serving directory, back to its root, or into a reserved directory;
 *   <li>it never reaches the directories the app keeps its own data in ({@link #RESERVED_DIRS}),
 *       under any letter case;
 *   <li>nothing new is created under a name starting with a dot, which the file tree hides.
 * </ul>
 *
 * <p>A resolved path is built on the real serving directory, so no part of it is a symlink. Code
 * about to touch the disk calls {@link #recheck} to confirm that is still true, since a folder can
 * be swapped for a link between the check and the use.
 */
@Component
public class StoragePaths {

  /** Where previous versions of files are kept. */
  public static final String VERSIONS_DIR = ".versions";

  /** Where the app keeps its own state, such as the generated share-link key. */
  public static final String INTERNAL_DIR = ".javadropbox";

  private static final Set<String> RESERVED_DIRS = Set.of(VERSIONS_DIR, INTERNAL_DIR);
  private static final String INVALID_PATH = "Invalid path";
  private static final String HIDDEN_NAME =
      "Names cannot start with a dot: files and folders named like that are hidden";

  /** The longest file or folder name most filesystems accept, and the width of the column. */
  static final int MAX_NAME_LENGTH = 255;

  private static final Logger log = LoggerFactory.getLogger(StoragePaths.class);

  private final Path root;
  private final Path realRoot;

  @Autowired
  public StoragePaths(@Value("${javadropbox.serving.directory}") String directory)
      throws IOException {
    this(Path.of(directory));
  }

  // For tests on another filesystem, such as an in-memory case-insensitive one.
  StoragePaths(Path directory) throws IOException {
    this.root = directory.toAbsolutePath().normalize();
    if (!Files.exists(root)) {
      Files.createDirectories(root);
      Files.writeString(
          root.resolve("Welcome to JavaDropbox.txt"),
          "This is your JavaDropbox storage folder. Anything you put here shows up in the web"
              + " interface.\n");
      log.info("Created the serving directory {}", root);
    }
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
   * @throws BadRequestException if the path escapes the serving directory, names a reserved one, or
   *     would create a hidden folder
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
    if (isReserved(relative)) {
      throw new BadRequestException(INVALID_PATH);
    }

    Path existing = candidate;
    while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      existing = existing.getParent();
    }
    if (existing == null) {
      throw new BadRequestException(INVALID_PATH);
    }
    // Keyed by the filesystem's own spelling of the part that exists, so every spelling a
    // case-insensitive filesystem accepts for one file gives it one key.
    Path created = existing.relativize(candidate);
    Path key = realRelativeOf(existing, raw).resolve(created);
    if (isReserved(key)) {
      throw new BadRequestException(INVALID_PATH);
    }
    // Whatever does not exist yet may be created by the caller, e.g. an upload's folder.
    if (hasHiddenName(created)) {
      throw new BadRequestException(HIDDEN_NAME);
    }
    return new StoragePath(realRoot.resolve(key), toKey(key));
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
    if (name.startsWith(".") || hasHiddenName(realRoot.relativize(parent.path()))) {
      throw new BadRequestException(HIDDEN_NAME);
    }
    return resolveItem(parent.isRoot() ? name : parent.key() + "/" + name);
  }

  /**
   * Checks again, right before a resolved path is used, that no part of it has been replaced by a
   * symlink since it was resolved.
   *
   * @param path a {@link StoragePath#path()}, or a folder along one
   * @return {@code path}, for use inline
   * @throws BadRequestException if part of the path is now a symlink
   */
  public static Path recheck(Path path) {
    for (Path part = path; part != null; part = part.getParent()) {
      if (Files.isSymbolicLink(part)) {
        log.warn("Rejected path that became a symlink after it was checked: {}", path);
        throw new BadRequestException(INVALID_PATH);
      }
    }
    return path;
  }

  // A case-insensitive filesystem (macOS, Windows) treats ".VERSIONS" as ".versions".
  private static boolean isReserved(Path relative) {
    if (relative.getNameCount() == 0) {
      return false;
    }
    String first = relative.getName(0).toString();
    return RESERVED_DIRS.stream().anyMatch(first::equalsIgnoreCase);
  }

  // The file tree skips such names, so an item created under one would never be seen again.
  private static boolean hasHiddenName(Path relative) {
    for (Path name : relative) {
      if (name.toString().startsWith(".")) {
        return true;
      }
    }
    return false;
  }

  // Normalizing only removes "..": a symlink can still lead anywhere, so refuse a path whose
  // existing part passes through one. Returns the real location of that part relative to the real
  // root, which also carries the filesystem's own spelling of each name.
  private Path realRelativeOf(Path existing, String raw) {
    for (Path part = existing; part.startsWith(root) && !part.equals(root); ) {
      if (Files.isSymbolicLink(part)) {
        log.warn("Rejected path through a symlink: {}", raw);
        throw new BadRequestException(INVALID_PATH);
      }
      part = part.getParent();
    }
    try {
      Path real = existing.toRealPath();
      if (!real.startsWith(realRoot)) {
        log.warn("Rejected path that leaves the serving directory through a symlink: {}", raw);
        throw new BadRequestException(INVALID_PATH);
      }
      return realRoot.relativize(real);
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
   * @param path the absolute filesystem path, inside the real serving directory
   * @param key the path relative to the serving directory with forward slashes, {@code ""} for the
   *     root, spelled as on disk where it exists. This is the form stored in the database and shown
   *     to clients.
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
