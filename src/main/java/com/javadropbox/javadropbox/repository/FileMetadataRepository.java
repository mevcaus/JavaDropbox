package com.javadropbox.javadropbox.repository;

import com.javadropbox.javadropbox.model.FileMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** Rows are looked up by their owner's id and their path relative to the owner's folder. */
public interface FileMetadataRepository
    extends JpaRepository<FileMetadata, Long>, FileMetadataLocking {

  @Query("select m from FileMetadata m where m.owner.id = :ownerId and m.path = :path")
  Optional<FileMetadata> findByPath(Long ownerId, String path);

  /** The row with this id, if the account owns it. */
  @Query("select m from FileMetadata m where m.id = :id and m.owner.id = :ownerId")
  Optional<FileMetadata> findOwned(Long id, Long ownerId);

  /** The ids of the row at {@code path} and of every row below it; see {@link #below}. */
  @Query(
      "select m.id from FileMetadata m where m.owner.id = :ownerId"
          + " and (m.path = :path or m.path like :below escape '!')")
  List<Long> findIdsAtOrBelow(Long ownerId, String path, String below);

  /**
   * Deletes the row at {@code path} and every row below it in one statement; see {@link #below}.
   * The database removes their versions with them and detaches their history.
   */
  @Modifying
  @Query(
      "delete from FileMetadata m where m.owner.id = :ownerId"
          + " and (m.path = :path or m.path like :below escape '!')")
  int deleteAtOrBelow(Long ownerId, String path, String below);

  /**
   * The LIKE pattern, with escape character {@code !}, for everything below the folder at {@code
   * path}. A folder called {@code a_} must not match {@code ab/...}, so its wildcards are escaped.
   */
  static String below(String path) {
    return path.replaceAll("[!%_]", "!$0") + "/%";
  }

  @Query("select m.id from FileMetadata m")
  List<Long> findAllIds();

  /** Every row an account owns, with the owner, in one query, for building its file tree. */
  @Query("select m from FileMetadata m join fetch m.owner o where o.id = :ownerId")
  List<FileMetadata> findAllOf(Long ownerId);

  @Modifying
  @Transactional
  @Query(
      "update FileMetadata m set m.lastAccessed = :time"
          + " where m.owner.id = :ownerId and m.path = :path")
  void markAccessed(Long ownerId, String path, Instant time);
}
