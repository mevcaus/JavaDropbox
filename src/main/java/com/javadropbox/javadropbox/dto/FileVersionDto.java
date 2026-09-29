package com.javadropbox.javadropbox.dto;

import com.javadropbox.javadropbox.model.FileVersion;
import java.time.Instant;

/**
 * One stored version of a file. {@code size} is in bytes, like everywhere else in the API; the
 * client formats it.
 */
public record FileVersionDto(
    Long id, Integer version, Long size, Instant createdAt, String createdBy) {

  public static FileVersionDto fromEntity(FileVersion entity) {
    return new FileVersionDto(
        entity.getId(),
        entity.getVersion(),
        entity.getSize(),
        entity.getCreatedAt(),
        entity.getCreatedBy() != null ? entity.getCreatedBy().getUsername() : "Unknown");
  }
}
