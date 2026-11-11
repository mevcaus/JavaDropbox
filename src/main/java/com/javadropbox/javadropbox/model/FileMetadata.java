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
import java.time.Instant;

/**
 * What the app knows about one file or folder, keyed by its owner and its path relative to the
 * owner's folder.
 */
@Entity
@Table(
    name = "file_metadata",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_file_metadata_owner_path",
            columnNames = {"user_id", "path"}))
public class FileMetadata {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Relative to the owner's folder, with forward slashes. One row per location per owner. */
  @Column(nullable = false, length = 4096)
  private String path;

  @Column(nullable = false)
  private String filename;

  private Long size;

  /**
   * The SHA-256 of the content the app last stored for this file, as hex; null for a folder, and
   * where the app never read the content as it stored it (from before hashes were kept, or copied
   * in by hand). A hint, not a promise: content changed outside the app since doesn't match it, so
   * it has to be checked against the content before anything is keyed by it.
   */
  @Column(length = 64)
  private String contentHash;

  @Column(nullable = false)
  private Boolean isDirectory;

  /** The number the live file will get when it is next archived as a previous version. */
  private Integer currentVersion = 1;

  /** The account whose folder the item is in. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User owner;

  private Instant createdAt;
  private Instant updatedAt;

  protected FileMetadata() {}

  public FileMetadata(String path, String filename, Long size, Boolean isDirectory, User owner) {
    this.path = path;
    this.filename = filename;
    this.owner = owner;
    Instant now = Instant.now();
    this.size = size;
    this.isDirectory = isDirectory;
    this.currentVersion = 1;
    this.createdAt = now;
    this.updatedAt = now;
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

  public String getContentHash() {
    return contentHash;
  }

  public void setContentHash(String contentHash) {
    this.contentHash = contentHash;
  }

  public void setSize(Long size) {
    this.size = size;
  }

  /** The bytes this item counts for in its owner's storage: a file's size, nothing for a folder. */
  public long countedBytes() {
    return Boolean.TRUE.equals(isDirectory) || size == null ? 0 : size;
  }

  public Boolean getIsDirectory() {
    return isDirectory;
  }

  public User getOwner() {
    return owner;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public Integer getCurrentVersion() {
    return currentVersion;
  }

  public void setCurrentVersion(Integer currentVersion) {
    this.currentVersion = currentVersion;
  }
}
