package com.javadropbox.javadropbox.dto;

import com.javadropbox.javadropbox.model.FileHistory;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record FileHistoryDto(
    Long id,
    @Nullable String filePath,
    @Nullable String filename,
    FileHistory.@Nullable ChangeType changeType,
    @Nullable Instant timestamp,
    String username,
    boolean success,
    @Nullable String errorMessage,
    @Nullable String details) {
  public static FileHistoryDto fromEntity(FileHistory entity) {
    return new FileHistoryDto(
        entity.getId(),
        entity.getFilePath(),
        entity.getFilename(),
        entity.getChangeType(),
        entity.getTimestamp(),
        entity.getUser() != null ? entity.getUser().getUsername() : "Unknown",
        entity.isSuccess(),
        entity.getErrorMessage(),
        entity.getDetails());
  }
}
