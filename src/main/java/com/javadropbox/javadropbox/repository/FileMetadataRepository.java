package com.javadropbox.javadropbox.repository;

import com.javadropbox.javadropbox.model.FileMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface FileMetadataRepository
    extends JpaRepository<FileMetadata, Long>, FileMetadataLocking {

  Optional<FileMetadata> findByPath(String path);

  /** The ids of the row at {@code path} and of every row below it; see {@link #below}. */
  @Query("select m.id from FileMetadata m where m.path = :path or m.path like :below escape '!'")
  List<Long> findIdsAtOrBelow(String path, String below);

  /**
   * Deletes the row at {@code path} and every row below it in one statement; see {@link #below}.
   * The database removes their versions with them and detaches their history.
   */
  @Modifying
  @Query("delete from FileMetadata m where m.path = :path or m.path like :below escape '!'")
  int deleteAtOrBelow(String path, String below);

  /**
   * The LIKE pattern, with escape character {@code !}, for everything below the folder at {@code
   * path}. A folder called {@code a_} must not match {@code ab/...}, so its wildcards are escaped.
   */
  static String below(String path) {
    return path.replaceAll("[!%_]", "!$0") + "/%";
  }

  @Query("select m.id from FileMetadata m")
  List<Long> findAllIds();

  /** Every row with its owner, in one query, for building the file tree. */
  @Query("select m from FileMetadata m left join fetch m.owner")
  List<FileMetadata> findAllWithOwner();

  @Modifying
  @Transactional
  @Query("update FileMetadata m set m.lastAccessed = :time where m.path = :path")
  void markAccessed(String path, Instant time);
}
