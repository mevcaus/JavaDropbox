package com.javadropbox.javadropbox.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** One entry in the audit log of file operations, successful or not. */
@Entity
@Table(name = "file_history")
public class FileHistory {

  static final int MESSAGE_LENGTH = 1024;
  static final int PATH_LENGTH = 4096;
  static final int NAME_LENGTH = 255;

  public enum ChangeType {
    UPLOAD,
    DELETE,
    CREATE_FOLDER,
    RESTORE
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // History outlives the file it describes: when the file's row is deleted the database clears
  // this link and the entry keeps its own copy of the path and name.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "file_id")
  @OnDelete(action = OnDeleteAction.SET_NULL)
  private FileMetadata fileMetadata;

  @Column(length = PATH_LENGTH)
  private String filePath;

  private String filename;

  @Enumerated(EnumType.STRING)
  private ChangeType changeType;

  private LocalDateTime timestamp;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id")
  private User user;

  private boolean success;

  @Column(length = MESSAGE_LENGTH)
  private String errorMessage;

  /** Extra information about a successful change, e.g. which version a restore came from. */
  @Column(length = MESSAGE_LENGTH)
  private String details;

  protected FileHistory() {}

  private FileHistory(
      FileMetadata fileMetadata,
      String filePath,
      String filename,
      ChangeType changeType,
      User user,
      boolean success) {
    this.fileMetadata = fileMetadata;
    // A failure can concern a path or name the storage rejected for being too long; the entry
    // recording that must still fit its columns.
    this.filePath = truncate(filePath, PATH_LENGTH);
    this.filename = truncate(filename, NAME_LENGTH);
    this.changeType = changeType;
    this.user = user;
    this.success = success;
    this.timestamp = LocalDateTime.now();
  }

  public static FileHistory success(FileMetadata file, ChangeType changeType, User user) {
    return new FileHistory(file, file.getPath(), file.getFilename(), changeType, user, true);
  }

  /** A successful change to an item that no longer has a metadata row, i.e. a deletion. */
  public static FileHistory success(
      String filePath, String filename, ChangeType changeType, User user) {
    return new FileHistory(null, filePath, filename, changeType, user, true);
  }

  public static FileHistory failure(
      String filePath, String filename, ChangeType changeType, User user, String errorMessage) {
    FileHistory history = new FileHistory(null, filePath, filename, changeType, user, false);
    history.errorMessage = truncate(errorMessage, MESSAGE_LENGTH);
    return history;
  }

  public FileHistory withDetails(String details) {
    this.details = truncate(details, MESSAGE_LENGTH);
    return this;
  }

  // An over-long value must not make the audit entry itself fail to save.
  private static String truncate(String value, int length) {
    return value == null || value.length() <= length ? value : value.substring(0, length);
  }

  public Long getId() {
    return id;
  }

  public FileMetadata getFileMetadata() {
    return fileMetadata;
  }

  public String getFilePath() {
    return filePath;
  }

  public String getFilename() {
    return filename;
  }

  public ChangeType getChangeType() {
    return changeType;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public User getUser() {
    return user;
  }

  public boolean isSuccess() {
    return success;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public String getDetails() {
    return details;
  }
}
