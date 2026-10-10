package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.model.User;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place a client-supplied path is turned into a location in the {@link FileStore}. Each
 * account's files are in a folder of its own, {@code .users/<id>} in the store ({@link #home}), and
 * every storage operation resolves its paths inside one of those, so the rules live in one place:
 *
 * <ul>
 *   <li>the result stays inside the account's folder after {@code ..} is normalized away, so it
 *       never reaches another account's files or the app's own;
 *   <li>it does not pass through a symlink: the file tree never shows one, and one could lead
 *       outside the folder, back to its root, or into a reserved directory;
 *   <li>it never reaches a folder named like one the app keeps its own data in ({@link
 *       #RESERVED_DIRS}), under any letter case;
 *   <li>nothing new is created under a name starting with a dot, which the file tree hides.
 * </ul>
 *
 * <p>The app's own state, such as the search index, is kept on the local disk in {@link
 * #internalDir} whichever store holds the files.
 */
@Component
public class StoragePaths {

  /** Where previous versions of files are kept. */
  public static final String VERSIONS_DIR = ".versions";

  /** Where the app keeps its own state. */
  public static final String INTERNAL_DIR = ".javadropbox";

  /** Where each account's files are kept, in a folder named after the account's id. */
  public static final String HOMES_DIR = ".users";

  /** Put in a new store, and given to the first account by setup (see LooseFileAdoption). */
  static final String WELCOME_FILE = "Welcome to JavaDropbox.txt";

  static final String WELCOME_TEXT =
      "This is your JavaDropbox storage folder. Anything you put here shows up in the web"
          + " interface.\n";

  private static final Set<String> RESERVED_DIRS = Set.of(VERSIONS_DIR, INTERNAL_DIR, HOMES_DIR);
  private static final String INVALID_PATH = "Invalid path";
  private static final String HIDDEN_NAME =
      "Names cannot start with a dot: files and folders named like that are hidden";

  /** The longest file or folder name most filesystems accept, and the width of the column. */
  static final int MAX_NAME_LENGTH = 255;

  private static final Logger log = LoggerFactory.getLogger(StoragePaths.class);

  private final FileStore store;
  private final Path internalDir;

  @Autowired
  public StoragePaths(FileStore store, @Value("${javadropbox.serving.directory}") String directory)
      throws IOException {
    this(store, Path.of(directory));
  }

  // For tests on the local disk, or on another filesystem such as an in-memory case-insensitive
  // one.
  StoragePaths(Path directory) throws IOException {
    this(new LocalFileStore(directory), directory);
  }

  StoragePaths(FileStore store, Path directory) throws IOException {
    this.store = store;
    // With the files kept elsewhere, the serving directory holds only the app's own state.
    Path serving = Files.createDirectories(directory.toAbsolutePath().normalize());
    // Built on the real directory, which may itself sit under a symlink (/var on macOS).
    this.internalDir = serving.toRealPath().resolve(INTERNAL_DIR);
  }

  /** Where the app keeps its own state, on the local disk. */
  public Path internalDir() {
    return internalDir;
  }

  /** The folder of an account's files, which is created if it does not exist yet. */
  public Home home(User user) {
    return home(user.getId());
  }

  /** Like {@link #home(User)}, by the account's id. */
  public Home home(long userId) {
    String key = HOMES_DIR + "/" + userId;
    try {
      store.createFolders(key);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new Home(store, userId, key);
  }

  /**
   * One account's folder, which that account's paths are resolved in. A path resolved here can
   * reach nothing outside it.
   */
  public static final class Home {

    private final FileStore store;
    private final long userId;
    private final String key;

    private Home(FileStore store, long userId, String key) {
      this.store = store;
      this.userId = userId;
      this.key = key;
    }

    public long userId() {
      return userId;
    }

    /** The folder's key in the store. */
    public String key() {
      return key;
    }

    /** What the search index calls the item at {@code key}: the account's id, then the path. */
    String indexKey(String key) {
      return key.isEmpty() ? String.valueOf(userId) : userId + "/" + key;
    }

    /**
     * Resolves a path relative to the account's folder. {@code null} and the empty string mean the
     * folder itself.
     *
     * @throws BadRequestException if the path escapes the folder, names a reserved one, or would
     *     create a hidden folder
     */
    public StoragePath resolve(String relativePath) {
      String raw = relativePath == null ? "" : relativePath;
      String relative = normalize(raw);
      if (isReserved(relative)) {
        throw new BadRequestException(INVALID_PATH);
      }
      // Keyed by the store's own spelling of the part that exists, so every spelling a
      // case-insensitive filesystem accepts for one file gives it one key.
      String spelled;
      try {
        spelled = store.spelling(key, relative);
      } catch (InvalidPathException e) {
        throw new BadRequestException(INVALID_PATH);
      }
      if (isReserved(spelled)) {
        throw new BadRequestException(INVALID_PATH);
      }
      // Whatever does not exist yet may be created by the caller, e.g. an upload's folder, so it
      // must not be hidden. A hidden name that already exists may be reached.
      int hidden = endOfLastHiddenName(spelled);
      if (hidden >= 0 && !exists(child(key, spelled.substring(0, hidden)))) {
        throw new BadRequestException(HIDDEN_NAME);
      }
      return new StoragePath(this, spelled);
    }

    /**
     * Like {@link #resolve} but refuses the folder itself, for operations that act on one item --
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
      if (name.startsWith(".") || endOfLastHiddenName(parent.key()) >= 0) {
        throw new BadRequestException(HIDDEN_NAME);
      }
      return resolveItem(parent.isRoot() ? name : parent.key() + "/" + name);
    }

    private boolean exists(String key) {
      try {
        return store.exists(key);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  /**
   * {@code raw} as a path relative to the folder it is resolved in, with {@code .}, {@code ..} and
   * empty segments taken out, as a filesystem would.
   *
   * @throws BadRequestException if it climbs out of the folder, is absolute, or cannot be a name
   */
  private static String normalize(String raw) {
    if (raw.indexOf('\0') >= 0 || !isWellFormed(raw)) {
      throw new BadRequestException(INVALID_PATH);
    }
    if (raw.startsWith("/")) {
      log.warn("Rejected path outside the account's folder: {}", raw);
      throw new BadRequestException(INVALID_PATH);
    }
    Deque<String> segments = new ArrayDeque<>();
    for (String segment : raw.split("/")) {
      if (segment.isEmpty() || segment.equals(".")) {
        continue;
      }
      if (segment.equals("..")) {
        if (segments.isEmpty()) {
          log.warn("Rejected path outside the account's folder: {}", raw);
          throw new BadRequestException(INVALID_PATH);
        }
        segments.removeLast();
      } else {
        segments.addLast(segment);
      }
    }
    return String.join("/", segments);
  }

  // No name on disk or in S3 can hold half of a surrogate pair: it has no encoding.
  private static boolean isWellFormed(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)
          && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        i++;
      } else if (Character.isSurrogate(c)) {
        return false;
      }
    }
    return true;
  }

  // A case-insensitive filesystem (macOS, Windows) treats ".VERSIONS" as ".versions".
  private static boolean isReserved(String relative) {
    if (relative.isEmpty()) {
      return false;
    }
    int slash = relative.indexOf('/');
    String first = slash < 0 ? relative : relative.substring(0, slash);
    return RESERVED_DIRS.stream().anyMatch(first::equalsIgnoreCase);
  }

  // The file tree skips names starting with a dot, so an item created under one would never be
  // seen again. Returns where the last such name in key ends, or -1 if it has none.
  private static int endOfLastHiddenName(String key) {
    int end = -1;
    int start = 0;
    while (start < key.length()) {
      int slash = key.indexOf('/', start);
      int segmentEnd = slash < 0 ? key.length() : slash;
      if (key.charAt(start) == '.') {
        end = segmentEnd;
      }
      start = segmentEnd + 1;
    }
    return end;
  }

  private static String child(String folder, String key) {
    return key.isEmpty() ? folder : folder + "/" + key;
  }

  /**
   * A validated location inside an account's folder.
   *
   * @param home the account's folder
   * @param key the path relative to the account's folder with forward slashes, {@code ""} for the
   *     folder itself, spelled as the store spells it where it exists. This is the form stored in
   *     the database and shown to clients.
   */
  public record StoragePath(Home home, String key) {

    public boolean isRoot() {
      return key.isEmpty();
    }

    /** The last segment of the path; the account's id for its folder itself. */
    public String name() {
      return isRoot() ? String.valueOf(home.userId()) : key.substring(key.lastIndexOf('/') + 1);
    }

    /** Where it is in the store. */
    public String storeKey() {
      return child(home.key(), key);
    }
  }
}
