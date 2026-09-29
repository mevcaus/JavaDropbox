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
import java.time.LocalDateTime;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** A previous version of a file, kept under the versions directory. */
@Entity
@Table(name = "file_versions")
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

  private LocalDateTime createdAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id")
  private User createdBy;

  protected FileVersion() {}

  public FileVersion(
      FileMetadata fileMetadata,
      Integer version,
      String storedFilename,
      Long size,
      User createdBy) {
    this.fileMetadata = fileMetadata;
    this.version = version;
    this.storedFilename = storedFilename;
    this.size = size;
    this.createdBy = createdBy;
    this.createdAt = LocalDateTime.now();
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

  public String getStoredFilename() {
    return storedFilename;
  }

  public Long getSize() {
    return size;
  }

  public User getCreatedBy() {
    return createdBy;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
