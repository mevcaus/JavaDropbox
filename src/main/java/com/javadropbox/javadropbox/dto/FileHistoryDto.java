package com.javadropbox.javadropbox.dto;

import com.javadropbox.javadropbox.model.FileHistory;
import java.time.Instant;

public record FileHistoryDto(
    Long id,
    String filePath,
    String filename,
    FileHistory.ChangeType changeType,
    Instant timestamp,
    String username,
    boolean success,
    String errorMessage) {
  public static FileHistoryDto fromEntity(FileHistory entity) {
    return new FileHistoryDto(
        entity.getId(),
        entity.getFilePath(),
        entity.getFilename(),
        entity.getChangeType(),
        Timestamps.toInstant(entity.getTimestamp()),
        entity.getUser() != null ? entity.getUser().getUsername() : "Unknown",
        entity.isSuccess(),
        entity.getErrorMessage());
  }
}
