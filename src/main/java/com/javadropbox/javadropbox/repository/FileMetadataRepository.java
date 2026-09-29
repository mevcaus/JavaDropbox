package com.javadropbox.javadropbox.repository;

import com.javadropbox.javadropbox.model.FileMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, Long> {

  Optional<FileMetadata> findByPath(String path);

  /** Everything below a folder. Spring Data escapes LIKE wildcards in the prefix. */
  List<FileMetadata> findByPathStartingWith(String pathPrefix);

  /** Every row with its owner, in one query, for building the file tree. */
  @Query("select m from FileMetadata m left join fetch m.owner")
  List<FileMetadata> findAllWithOwner();

  @Modifying
  @Transactional
  @Query("update FileMetadata m set m.lastAccessed = :time where m.path = :path")
  void markAccessed(String path, Instant time);
}
