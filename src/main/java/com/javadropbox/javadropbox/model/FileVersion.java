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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** A previous version of a file, kept under the versions directory. */
@Entity
@Table(
    name = "file_versions",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_file_versions_file_version",
            columnNames = {"file_id", "version"}))
public class FileVersion {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // A version is meaningless without its file, so the database removes it with the file.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "file_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private FileMetadata fileMetadata;

  @Column(nullable = false)
  private Integer version;

  /** Location relative to the versions directory. */
  @Column(nullable = false)
  private String storedFilename;

  private Long size;

  /**
   * The SHA-256 of the version's content, as hex; null where it isn't known. A hint, like the
   * file's: it has to be checked against the content before anything is keyed by it.
   */
  @Column(length = 64)
  private String contentHash;

  private Instant createdAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id")
  private User createdBy;

  protected FileVersion() {}

  public FileVersion(
      FileMetadata fileMetadata,
      Integer version,
      String storedFilename,
      Long size,
      String contentHash,
      User createdBy) {
    this.fileMetadata = fileMetadata;
    this.version = version;
    this.storedFilename = storedFilename;
    this.size = size;
    this.contentHash = contentHash;
    this.createdBy = createdBy;
    this.createdAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public FileMetadata getFileMetadata() {
    return fileMetadata;
  }

  public Integer getVersion() {
    return version;
  }

  public String getContentHash() {
    return contentHash;
  }

  public String getStoredFilename() {
    return storedFilename;
  }

  public Long getSize() {
    return size;
  }

  public User getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
