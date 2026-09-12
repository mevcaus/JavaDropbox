package com.javadropbox.javadropbox.dto;

import com.javadropbox.javadropbox.model.FileVersion;
import java.time.Instant;

public class FileVersionDto {
  private Long id;
  private Integer version;
  private String size;
  private Instant createdAt;
  private String createdBy;

  public static FileVersionDto fromEntity(FileVersion entity) {
    FileVersionDto dto = new FileVersionDto();
    dto.setId(entity.getId());
    dto.setVersion(entity.getVersion());
    dto.setSize(formatSize(entity.getSize()));
    dto.setCreatedAt(Timestamps.toInstant(entity.getCreatedAt()));
    dto.setCreatedBy(
        entity.getCreatedBy() != null ? entity.getCreatedBy().getUsername() : "Unknown");
    return dto;
  }

  private static String formatSize(Long size) {
    if (size == null) return "0 B";
    if (size < 1024) return size + " B";
    if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
    return String.format("%.1f MB", size / (1024.0 * 1024.0));
  }

  // Getters and Setters
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Integer getVersion() {
    return version;
  }

  public void setVersion(Integer version) {
    this.version = version;
  }

  public String getSize() {
    return size;
  }

  public void setSize(String size) {
    this.size = size;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }
}
