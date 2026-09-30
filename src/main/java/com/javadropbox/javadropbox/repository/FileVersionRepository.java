package com.javadropbox.javadropbox.repository;

import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.FileVersion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileVersionRepository extends JpaRepository<FileVersion, Long> {

  List<FileVersion> findByFileMetadataOrderByVersionDesc(FileMetadata fileMetadata);

  boolean existsByStoredFilename(String storedFilename);
}
