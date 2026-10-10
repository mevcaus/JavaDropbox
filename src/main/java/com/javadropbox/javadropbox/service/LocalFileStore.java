package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.exception.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;

/**
 * A {@link FileStore} in a folder on the server's disk, the serving directory: each key is a path
 * relative to it.
 *
 * <p>Keys are resolved on the real serving directory, so no part of the path is a symlink, and each
 * operation checks again with {@link #recheck} right before it touches the disk: a folder can be
 * swapped for a link between a path's check and its use. Symlinks are never followed, and are not
 * items, nor are special files such as FIFOs, which reading would block on.
 */
public class LocalFileStore implements FileStore {

  private static final String INVALID_PATH = "Invalid path";

  private static final Logger log = LoggerFactory.getLogger(LocalFileStore.class);

  private final Path root;
  private final Path realRoot;

  /** Creates the folder, with a welcome file, if it does not exist. */
  public LocalFileStore(Path directory) throws IOException {
    this.root = directory.toAbsolutePath().normalize();
    if (!Files.exists(root)) {
      Files.createDirectories(root);
      Files.writeString(root.resolve(StoragePaths.WELCOME_FILE), StoragePaths.WELCOME_TEXT);
      log.info("Created the serving directory {}", root);
    }
    this.realRoot = root.toRealPath();
  }

  /** The folder, as configured. */
  public Path root() {
    return root;
  }

  /**
   * Where the item at {@code key} is on disk: on the real serving directory, where no part of the
   * path is a symlink unless one has been swapped in since.
   */
  public Path path(String key) {
    Path path = key.isEmpty() ? realRoot : realRoot.resolve(key).normalize();
    if (!path.startsWith(realRoot)) {
      throw new IllegalArgumentException("Key outside the store: " + key);
    }
    return path;
  }

  @Override
  public String description() {
    return "the folder " + root;
  }

  /**
   * Checks again, right before a path is used, that no part of it has been replaced by a symlink
   * since it was resolved.
   *
   * @return {@code path}, for use inline
   * @throws BadRequestException if part of the path is now a symlink
   */
  static Path recheck(Path path) {
    for (Path part = path; part != null; part = part.getParent()) {
      if (Files.isSymbolicLink(part)) {
        log.warn("Rejected path that became a symlink after it was checked: {}", path);
        throw new BadRequestException(INVALID_PATH);
      }
    }
    return path;
  }

  // Normalizing only removes "..": a symlink can still lead anywhere, so refuse a path whose
  // existing part passes through one. The real location of that part carries the filesystem's own
  // spelling of each name.
  @Override
  public String spelling(String base, String relative) {
    Path folder = path(base);
    Path candidate = relative.isEmpty() ? folder : folder.resolve(relative);
    Path existing = candidate;
    while (existing.startsWith(folder) && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      existing = existing.getParent();
    }
    if (!existing.startsWith(folder)) {
      throw new BadRequestException(INVALID_PATH);
    }
    for (Path part = existing; part.startsWith(folder) && !part.equals(folder); ) {
      if (Files.isSymbolicLink(part)) {
        log.warn("Rejected path through a symlink: {}", relative);
        throw new BadRequestException(INVALID_PATH);
      }
      part = part.getParent();
    }
    Path real;
    try {
      // Only a link swapped in since the loop above could lead elsewhere.
      real = existing.toRealPath();
    } catch (IOException e) {
      // Removed, or replaced by a dangling or looping link, while it was being checked.
      throw new BadRequestException(INVALID_PATH);
    }
    if (!real.startsWith(folder)) {
      log.warn("Rejected path that leaves its folder: {}", relative);
      throw new BadRequestException(INVALID_PATH);
    }
    return toKey(folder.relativize(real).resolve(existing.relativize(candidate)));
  }

  // As Files.exists would have it: what cannot be read is not there, which includes a path that
  // runs through a file ("Not a directory").
  @Override
  public Optional<Entry> stat(String key) {
    BasicFileAttributes attributes;
    try {
      attributes =
          Files.readAttributes(
              recheck(path(key)), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (IOException e) {
      return Optional.empty();
    }
    return isItem(attributes) ? Optional.of(entry(key, attributes)) : Optional.empty();
  }

  @Override
  public void walk(String key, Visitor visitor) throws IOException {
    Path start = recheck(path(key));
    if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(
        start,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes)
              throws IOException {
            // Swapped for a link since it was listed: leave it out rather than follow it.
            if (!dir.equals(start) && hasLinkAlong(dir)) {
              log.warn("Left out {}, which became a symlink during a walk", dir);
              return FileVisitResult.SKIP_SUBTREE;
            }
            return visitor.visit(entry(keyOf(dir), attributes));
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            // A link is reported as itself rather than followed; neither it nor a FIFO is an item.
            if (!isItem(attributes)) {
              return FileVisitResult.CONTINUE;
            }
            return visitor.visit(entry(keyOf(file), attributes)) == FileVisitResult.TERMINATE
                ? FileVisitResult.TERMINATE
                : FileVisitResult.CONTINUE;
          }

          // Removed since its folder was listed, or unreadable: the rest is still worth visiting.
          @Override
          public FileVisitResult visitFileFailed(Path file, IOException e) {
            log.debug("Could not visit {}: {}", file, e.toString());
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException e) {
            if (e != null) {
              log.debug("Could not finish visiting {}: {}", dir, e.toString());
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static boolean hasLinkAlong(Path path) {
    try {
      recheck(path);
      return false;
    } catch (BadRequestException e) {
      return true;
    }
  }

  @Override
  public List<Entry> list(String key) throws IOException {
    List<Entry> entries = new ArrayList<>();
    try (DirectoryStream<Path> children = Files.newDirectoryStream(recheck(path(key)))) {
      for (Path child : children) {
        try {
          BasicFileAttributes attributes =
              Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          if (isItem(attributes)) {
            entries.add(entry(keyOf(child), attributes));
          }
        } catch (NoSuchFileException e) {
          // Removed since the folder was listed.
        }
      }
    }
    return entries;
  }

  @Override
  public InputStream open(String key) throws IOException {
    return Files.newInputStream(recheck(path(key)), LinkOption.NOFOLLOW_LINKS);
  }

  @Override
  public Resource resource(Entry file) {
    return new FileResource(path(file.key()));
  }

  @Override
  public <T> T readLocally(String key, LocalReader<T> reader) throws IOException {
    return reader.read(recheck(path(key)));
  }

  /**
   * A hidden file next to {@code key}, so moving it into place is a rename. It gets the permissions
   * any new file gets, which the rename carries over; a temp file would be readable only by the
   * app's user.
   */
  @Override
  public String scratchBeside(String key) throws IOException {
    Path folder = recheck(path(key).getParent());
    return keyOf(Files.createFile(folder.resolve(".upload-" + UUID.randomUUID() + ".tmp")));
  }

  @Override
  public long write(String key, InputStream content, long length) throws IOException {
    return Files.copy(content, recheck(path(key)), StandardCopyOption.REPLACE_EXISTING);
  }

  @Override
  public void copy(String from, String to) throws IOException {
    Files.copy(
        recheck(path(from)),
        recheck(path(to)),
        StandardCopyOption.REPLACE_EXISTING,
        LinkOption.NOFOLLOW_LINKS);
  }

  // Never REPLACE_EXISTING: what is in the way is someone else's, and failing beats overwriting it.
  @Override
  public void move(String from, String to) throws IOException {
    Path target = recheck(path(to));
    Files.createDirectories(target.getParent());
    Files.move(recheck(path(from)), target);
  }

  @Override
  public void replace(String from, String to) throws IOException {
    Path source = recheck(path(from));
    Path target = path(to);
    // A rename replaces a link at the target itself, but follows one along its folders.
    recheck(target.getParent());
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  @Override
  public void touch(String key) throws IOException {
    Files.setLastModifiedTime(recheck(path(key)), FileTime.from(Instant.now()));
  }

  @Override
  public boolean createFile(String key) throws IOException {
    try {
      Files.createFile(recheck(path(key)));
      return true;
    } catch (FileAlreadyExistsException e) {
      return false;
    }
  }

  @Override
  public void createFolder(String key) throws IOException {
    Files.createDirectory(recheck(path(key)));
  }

  @Override
  public void createFolders(String key) throws IOException {
    Path folder = recheck(path(key));
    if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectories(folder);
    }
  }

  @Override
  public void delete(String key) throws IOException {
    Files.deleteIfExists(recheck(path(key)));
  }

  /**
   * Symlinks inside the folder are deleted themselves, never followed, so a link cannot take files
   * outside the folder down with it.
   */
  @Override
  public void deleteRecursively(String key) throws IOException {
    Path start = recheck(path(key));
    if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
      Files.deleteIfExists(start);
      return;
    }
    Files.walkFileTree(
        start,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            // The file itself may be a link, which is deleted rather than followed.
            recheck(file.getParent());
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (exc != null) {
              throw exc;
            }
            Files.delete(recheck(dir));
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static boolean isItem(BasicFileAttributes attributes) {
    return attributes.isDirectory() || attributes.isRegularFile();
  }

  private static Entry entry(String key, BasicFileAttributes attributes) {
    return new Entry(
        key,
        attributes.isDirectory(),
        attributes.isDirectory() ? 0 : attributes.size(),
        attributes.creationTime().toInstant(),
        attributes.lastModifiedTime().toInstant());
  }

  private String keyOf(Path path) {
    return toKey(realRoot.relativize(path));
  }

  private static String toKey(Path relative) {
    return relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
  }

  /**
   * A file to send, opened only when the response is written and checked again for symlinks then.
   */
  private static final class FileResource extends AbstractResource {

    private final Path path;

    FileResource(Path path) {
      this.path = path;
    }

    @Override
    public InputStream getInputStream() throws IOException {
      return Files.newInputStream(recheck(path), LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public long contentLength() throws IOException {
      return Files.size(recheck(path));
    }

    @Override
    public long lastModified() throws IOException {
      return Files.getLastModifiedTime(recheck(path), LinkOption.NOFOLLOW_LINKS).toMillis();
    }

    @Override
    public boolean exists() {
      return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public String getFilename() {
      return path.getFileName().toString();
    }

    @Override
    public String getDescription() {
      return "file [" + path + "]";
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof FileResource that && path.equals(that.path);
    }

    @Override
    public int hashCode() {
      return path.hashCode();
    }
  }
}
