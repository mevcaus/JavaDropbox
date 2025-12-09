package com.javadropbox.javadropbox.model;

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

@Entity
@Table(name = "file_history")
public class FileHistory {

    public enum ChangeType {
        UPLOAD,
        DELETE,
        CREATE_FOLDER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id")
    private FileMetadata fileMetadata;

    // We store path/filename here too in case the original metadata is deleted
    private String filePath;
    private String filename;

    @Enumerated(EnumType.STRING)
    private ChangeType changeType;

    private LocalDateTime timestamp;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    public FileHistory() {
        this.timestamp = LocalDateTime.now();
    }

    public FileHistory(FileMetadata fileMetadata, ChangeType changeType, User user) {
        this.fileMetadata = fileMetadata;
        if (fileMetadata != null) {
            this.filePath = fileMetadata.getPath();
            this.filename = fileMetadata.getFilename();
        }
        this.changeType = changeType;
        this.user = user;
        this.timestamp = LocalDateTime.now();
    }

    // Constructor for Deletions where metadata might be gone or we just want to
    // record the path
    public FileHistory(String filePath, String filename, ChangeType changeType, User user) {
        this.filePath = filePath;
        this.filename = filename;
        this.changeType = changeType;
        this.user = user;
        this.timestamp = LocalDateTime.now();
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public FileMetadata getFileMetadata() {
        return fileMetadata;
    }

    public void setFileMetadata(FileMetadata fileMetadata) {
        this.fileMetadata = fileMetadata;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public void setChangeType(ChangeType changeType) {
        this.changeType = changeType;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }
}
