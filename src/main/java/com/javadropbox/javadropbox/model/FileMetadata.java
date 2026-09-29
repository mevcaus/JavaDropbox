package com.javadropbox.javadropbox.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/** What the app knows about one file or folder, keyed by its path relative to the storage root. */
@Entity
@Table(
    name = "file_metadata",
    uniqueConstraints = @UniqueConstraint(name = "uk_file_metadata_path", columnNames = "path"))
public class FileMetadata {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Relative to the storage root, with forward slashes. Unique: one row per location. */
  @Column(nullable = false, length = 4096)
  private String path;

  @Column(nullable = false)
  private String filename;

  private Long size;

  @Column(nullable = false)
  private Boolean isDirectory;

  /** The number the live file will get when it is next archived as a previous version. */
  private Integer currentVersion = 1;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id")
  private User owner;

  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private LocalDateTime lastAccessed;

  protected FileMetadata() {}

  public FileMetadata(String path, String filename, Long size, Boolean isDirectory, User owner) {
    this.path = path;
    this.filename = filename;
    startOver(size, isDirectory, owner);
  }

  /**
   * Resets the row to describe a brand-new item at the same path. Used when a row outlived its
   * file, e.g. because the file was removed outside the app, so the new item does not inherit the
   * old one's owner, dates or version numbering.
   */
  public void startOver(Long size, Boolean isDirectory, User owner) {
    LocalDateTime now = LocalDateTime.now();
    this.size = size;
    this.isDirectory = isDirectory;
    this.owner = owner;
    this.currentVersion = 1;
    this.createdAt = now;
    this.updatedAt = now;
    this.lastAccessed = now;
  }

  public Long getId() {
    return id;
  }

  public String getPath() {
    return path;
  }

  public String getFilename() {
    return filename;
  }

  public Long getSize() {
    return size;
  }

  public void setSize(Long size) {
    this.size = size;
  }

  public Boolean getIsDirectory() {
    return isDirectory;
  }

  public User getOwner() {
    return owner;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  public LocalDateTime getLastAccessed() {
    return lastAccessed;
  }

  public Integer getCurrentVersion() {
    return currentVersion;
  }

  public void setCurrentVersion(Integer currentVersion) {
    this.currentVersion = currentVersion;
  }
}
