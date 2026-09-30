package com.javadropbox.javadropbox.repository;

import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.FileVersion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface FileVersionRepository extends JpaRepository<FileVersion, Long> {

  List<FileVersion> findByFileMetadataOrderByVersionDesc(FileMetadata fileMetadata);

  boolean existsByStoredFilename(String storedFilename);

  @Query("select v.storedFilename from FileVersion v")
  List<String> findAllStoredFilenames();

  /**
   * Where the versions of the row at {@code path} and of every row below it are stored; see {@link
   * FileMetadataRepository#below}.
   */
  @Query(
      "select v.storedFilename from FileVersion v join v.fileMetadata m"
          + " where m.path = :path or m.path like :below escape '!'")
  List<String> findStoredFilenamesAtOrBelow(String path, String below);

  /** Deletes the versions of the row at {@code path} and of every row below it. */
  @Modifying
  @Query(
      "delete from FileVersion v where v.fileMetadata.id in (select m.id from FileMetadata m"
          + " where m.path = :path or m.path like :below escape '!')")
  int deleteAtOrBelow(String path, String below);
}
