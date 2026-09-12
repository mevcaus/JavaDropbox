package com.javadropbox.javadropbox.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class FileTreeNode {
  private String name;
  private boolean isDirectory;
  private long size;
  private Long id; // Added ID for versioning support
  private Instant createdDate;
  private Instant lastModified;
  private String ownerName;
  private String relativePath; // Added relative path for navigation
  private List<FileTreeNode> children;

  public FileTreeNode(String name, boolean isDirectory, long size) {
    this.name = name;
    this.isDirectory = isDirectory;
    this.size = size;
    this.children = new ArrayList<>();
  }

  public FileTreeNode(String name, boolean isDirectory, long size, String relativePath) {
    this(name, isDirectory, size);
    this.relativePath = relativePath;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public boolean getIsDirectory() {
    return isDirectory;
  }

  public void setIsDirectory(boolean isDirectory) {
    this.isDirectory = isDirectory;
  }

  public long getSize() {
    return size;
  }

  public void setSize(long size) {
    this.size = size;
  }

  public List<FileTreeNode> getChildren() {
    return children;
  }

  public void setChildren(List<FileTreeNode> children) {
    this.children = children;
  }

  public Instant getCreatedDate() {
    return createdDate;
  }

  public void setCreatedDate(Instant createdDate) {
    this.createdDate = createdDate;
  }

  public Instant getLastModified() {
    return lastModified;
  }

  public void setLastModified(Instant lastModified) {
    this.lastModified = lastModified;
  }

  public String getOwnerName() {
    return ownerName;
  }

  public void setOwnerName(String ownerName) {
    this.ownerName = ownerName;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getRelativePath() {
    return relativePath;
  }

  public void setRelativePath(String relativePath) {
    this.relativePath = relativePath;
  }
}
