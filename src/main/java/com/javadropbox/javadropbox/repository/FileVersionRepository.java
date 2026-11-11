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
   * The stored filenames of the versions of the item at {@code path} and of everything below it.
   */
  @Query(
      "select v.storedFilename from FileVersion v join v.fileMetadata m where m.owner.id = :ownerId"
          + " and (m.path = :path or m.path like :below escape '!')")
  List<String> findStoredFilenamesAtOrBelow(Long ownerId, String path, String below);

  /** The bytes of the versions of the item at {@code path} and of everything below it. */
  @Query(
      "select coalesce(sum(v.size), 0) from FileVersion v join v.fileMetadata m"
          + " where m.owner.id = :ownerId and (m.path = :path or m.path like :below escape '!')")
  long sizeAtOrBelow(Long ownerId, String path, String below);

  @Modifying
  @Query(
      "delete from FileVersion v where v.fileMetadata.id in (select m.id from FileMetadata m"
          + " where m.owner.id = :ownerId and (m.path = :path or m.path like :below escape '!'))")
  int deleteAtOrBelow(Long ownerId, String path, String below);
}
