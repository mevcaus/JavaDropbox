package com.javadropbox.javadropbox.repository;

import com.javadropbox.javadropbox.model.FileMetadata;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, Long> {
  Optional<FileMetadata> findByPath(String path);

  List<FileMetadata> findByPathStartsWith(String pathPrefix);
}
