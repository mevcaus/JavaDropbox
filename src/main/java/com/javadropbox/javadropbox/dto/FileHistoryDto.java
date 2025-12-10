package com.javadropbox.javadropbox.dto;

import com.javadropbox.javadropbox.model.FileHistory;

import java.time.LocalDateTime;

public record FileHistoryDto(
        Long id,
        String filePath,
        String filename,
        FileHistory.ChangeType changeType,
        LocalDateTime timestamp,
        String username,
        boolean success,
        String errorMessage) {
    public static FileHistoryDto fromEntity(FileHistory entity) {
        return new FileHistoryDto(
                entity.getId(),
                entity.getFilePath(),
                entity.getFilename(),
                entity.getChangeType(),
                entity.getTimestamp(),
                entity.getUser() != null ? entity.getUser().getUsername() : "Unknown",
                entity.isSuccess(),
                entity.getErrorMessage());
    }
}
