package com.javadropbox.javadropbox.repository;

import com.javadropbox.javadropbox.model.FileMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Rows are looked up by their owner's id and their path relative to the owner's folder. */
public interface FileMetadataRepository
    extends JpaRepository<FileMetadata, Long>, FileMetadataLocking {

  @Query("select m from FileMetadata m where m.owner.id = :ownerId and m.path = :path")
  Optional<FileMetadata> findByPath(Long ownerId, String path);

  /** The row with this id, if the account owns it. */
  @Query("select m from FileMetadata m where m.id = :id and m.owner.id = :ownerId")
  Optional<FileMetadata> findOwned(Long id, Long ownerId);

  /**
   * Locks the row at {@code path} and every row below it until the transaction ends, in id order so
   * that two requests locking overlapping rows take turns rather than deadlock; see {@link #below}.
   * A change to one of them under way, such as a replace, finishes first.
   *
   * @return their ids
   */
  @Query(
      value =
          "select id from file_metadata where user_id = :ownerId"
              + " and (path = :path or path like :below escape '!') order by id for update",
      nativeQuery = true)
  List<Long> lockIdsAtOrBelow(Long ownerId, String path, String below);

  /**
   * Deletes the row at {@code path} and every row below it in one statement, and says what it
   * deleted: exactly those rows, even one added since they were locked. Their versions have to be
   * deleted first, so that they are counted; the database detaches their history.
   *
   * @return one row: the bytes the deleted files counted for, and how many rows went
   */
  @Query(
      value =
          "with deleted as (delete from file_metadata where user_id = :ownerId"
              + " and (path = :path or path like :below escape '!')"
              + " returning size, is_directory)"
              + " select coalesce(sum(case when is_directory then 0 else coalesce(size, 0) end), 0),"
              + " count(*) from deleted",
      nativeQuery = true)
  List<Object[]> deleteAtOrBelowCounting(Long ownerId, String path, String below);

  /**
   * The LIKE pattern, with escape character {@code !}, for everything below the folder at {@code
   * path}. A folder called {@code a_} must not match {@code ab/...}, so its wildcards are escaped.
   */
  static String below(String path) {
    return path.replaceAll("[!%_]", "!$0") + "/%";
  }

  /**
   * Adds a row for a file or folder at {@code path} unless the account already has a row there. A
   * concurrent insert of the same path makes this one wait for the other transaction and then add
   * nothing, so two requests adding the same item both go on rather than one failing.
   *
   * <p>The values are those {@code FileMetadata.startOver} gives a new item, apart from the dates;
   * keep the two in step.
   *
   * @return 1 if the row was added, 0 if there already was one
   */
  @Modifying
  @Query(
      value =
          "insert into file_metadata (user_id, path, filename, size, is_directory,"
              + " current_version, created_at, updated_at)"
              + " values (:ownerId, :path, :filename, :size, :isDirectory, 1, :created, :updated)"
              + " on conflict (user_id, path) do nothing",
      nativeQuery = true)
  int insertIfAbsent(
      Long ownerId,
      String path,
      String filename,
      long size,
      boolean isDirectory,
      Instant created,
      Instant updated);

  /** Like {@link #insertIfAbsent}, for a new, empty folder. */
  default int insertFolderIfAbsent(Long ownerId, String path, String filename, Instant now) {
    return insertIfAbsent(ownerId, path, filename, 0, true, now, now);
  }

  /** The ids of an account's rows at {@code paths}. */
  @Query("select m.id from FileMetadata m where m.owner.id = :ownerId and m.path in :paths")
  List<Long> findIdsOf(Long ownerId, List<String> paths);

  /** The path of every row an account owns. */
  @Query("select m.path from FileMetadata m where m.owner.id = :ownerId")
  List<String> findPathsOf(Long ownerId);

  @Query("select m.id from FileMetadata m")
  List<Long> findAllIds();

  /** Like {@link #findAllOf}, for the rows below a folder; see {@link #below}. */
  @Query(
      "select m from FileMetadata m join fetch m.owner o where o.id = :ownerId"
          + " and m.path like :below escape '!'")
  List<FileMetadata> findAllBelow(Long ownerId, String below);

  /** Every row an account owns, with the owner, in one query, for building its file tree. */
  @Query("select m from FileMetadata m join fetch m.owner o where o.id = :ownerId")
  List<FileMetadata> findAllOf(Long ownerId);
}
