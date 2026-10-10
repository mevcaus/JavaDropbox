package com.javadropbox.javadropbox.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.Resource;

/**
 * Where the accounts' files and their previous versions are kept: a folder on the server's disk
 * ({@link LocalFileStore}), or a bucket in S3 or a service compatible with it ({@link
 * S3FileStore}). The services above it work the same on either.
 *
 * <p>Items are addressed by key: a path relative to the store's root with forward slashes, such as
 * {@code .users/3/Documents/report.txt}, and {@code ""} for the root itself. Keys come from {@link
 * StoragePaths} or are built from ones that did, so none is absolute or climbs out with {@code ..}.
 *
 * <p>A store behaves like a filesystem whatever it is built on: an item is a file or a folder, a
 * folder can be empty, and deleting what a folder holds leaves the folder. Anything a store cannot
 * show as one of those, such as a symlink on disk, is not there as far as the app is concerned.
 * Missing items are reported as {@link NoSuchFileException} and taken names as {@link
 * FileAlreadyExistsException}, whichever store it is.
 */
public interface FileStore {

  /**
   * A file or folder in the store.
   *
   * @param size in bytes; 0 for a folder
   * @param created when it was created, or {@code null} where the store does not know
   * @param modified when it last changed, or {@code null} where the store does not know (a folder
   *     in S3 that is only there because something is stored inside it)
   */
  record Entry(String key, boolean isDirectory, long size, Instant created, Instant modified) {

    /** The last segment of the key; {@code ""} for the root. */
    public String name() {
      return key.substring(key.lastIndexOf('/') + 1);
    }
  }

  /** Visits the items of a walk, folders before what they hold. */
  interface Visitor {

    /**
     * @return {@link FileVisitResult#SKIP_SUBTREE} to leave out what a folder holds, {@link
     *     FileVisitResult#TERMINATE} to end the walk, otherwise {@link FileVisitResult#CONTINUE}
     */
    FileVisitResult visit(Entry entry) throws IOException;
  }

  /** Reads a file the store has made available on the local disk. */
  interface LocalReader<T> {
    T read(Path file) throws IOException;
  }

  /** What the store is, for the log: its folder, or its bucket. */
  String description();

  /**
   * {@code relative}, a path below the folder at {@code base}, with the part that exists spelled as
   * the store spells it. A filesystem that ignores letter case accepts {@code DOCS/a.txt} for
   * {@code Docs/a.txt}; both have to be the one key.
   *
   * @throws com.javadropbox.javadropbox.exception.BadRequestException if the way there passes
   *     through something that could lead outside {@code base}, such as a symlink
   */
  String spelling(String base, String relative);

  /** What is at {@code key}, or empty if nothing is. */
  Optional<Entry> stat(String key) throws IOException;

  default boolean exists(String key) throws IOException {
    return stat(key).isPresent();
  }

  /**
   * Visits the item at {@code key} and, for a folder, everything below it, each folder before what
   * it holds. Nothing is visited if nothing is at {@code key}. Items that go while the walk is
   * under way may or may not be visited.
   */
  void walk(String key, Visitor visitor) throws IOException;

  /** The items directly in the folder at {@code key}, in no particular order. */
  List<Entry> list(String key) throws IOException;

  /** Reads the file at {@code key}. */
  InputStream open(String key) throws IOException;

  /**
   * The content of {@code file} to send as a response, read only once the response is written.
   * Supports HTTP range requests without reading what comes before the range.
   */
  Resource resource(Entry file) throws IOException;

  /**
   * Hands {@code reader} the file at {@code key} on the local disk, for readers that need to move
   * around in a file rather than read it from start to end, such as one of a PDF. A store kept
   * elsewhere copies it to a temporary file first, which is deleted afterwards.
   */
  <T> T readLocally(String key, LocalReader<T> reader) throws IOException;

  /**
   * A new key for a hidden scratch file in the folder of {@code key}, which {@link #replace} can
   * then move onto it in one step. The scratch file may already exist, empty, or appear only when
   * it is written.
   */
  String scratchBeside(String key) throws IOException;

  /**
   * Writes {@code content} to the file at {@code key}, replacing what is there.
   *
   * @param length how many bytes {@code content} holds, or -1 if that is not known
   * @return the number of bytes written
   */
  long write(String key, InputStream content, long length) throws IOException;

  /** Copies the file at {@code from} to {@code to}, replacing what is there. */
  void copy(String from, String to) throws IOException;

  /**
   * Moves the file or folder at {@code from} to {@code to}, which must not exist yet. On the local
   * disk this is a rename, which keeps the modification time; in S3 it is a copy, which is new.
   *
   * @throws FileAlreadyExistsException if something is at {@code to}
   */
  void move(String from, String to) throws IOException;

  /**
   * Moves the file at {@code from} onto {@code to}, replacing what is there, in one step where the
   * store can: whoever reads {@code to} meanwhile sees the old content or the new, never part of
   * either.
   */
  void replace(String from, String to) throws IOException;

  /** Gives a file just moved to {@code key} a fresh modification time, if moving it did not. */
  void touch(String key) throws IOException;

  /**
   * Creates an empty file at {@code key} if nothing is there, as one step: of two requests trying
   * at once, one gets it.
   *
   * @return whether it was created
   */
  boolean createFile(String key) throws IOException;

  /**
   * Creates a folder at {@code key}, inside a folder that exists.
   *
   * @throws FileAlreadyExistsException if something is at {@code key}
   */
  void createFolder(String key) throws IOException;

  /** Creates a folder at {@code key} and any folders above it that are missing. */
  void createFolders(String key) throws IOException;

  /**
   * Deletes the file or the empty folder at {@code key}, if there is one. A folder that something
   * is in is refused or left as it is, never emptied.
   */
  void delete(String key) throws IOException;

  /**
   * Deletes the file or folder at {@code key} and everything in it, if anything is there. The
   * folder that held it stays.
   *
   * @throws NoSuchFileException if something it held went while it was being deleted
   */
  void deleteRecursively(String key) throws IOException;
}
