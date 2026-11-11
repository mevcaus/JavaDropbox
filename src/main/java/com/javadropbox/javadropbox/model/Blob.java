package com.javadropbox.javadropbox.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One distinct content, kept once in the store under its SHA-256, and how many files and versions
 * point at it.
 */
@Entity
@Table(name = "blobs")
public class Blob {

  /** The SHA-256 of the content, as 64 lowercase hex digits. */
  @Id
  @Column(length = 64)
  private String hash;

  @Column(nullable = false)
  private long size;

  @Column(nullable = false)
  private int referenceCount;

  @Column(nullable = false)
  private Instant createdAt;

  // Rows are only ever made and counted with single statements (#316), never built here and saved,
  // which could set a live count back.
  protected Blob() {}

  public String getHash() {
    return hash;
  }

  public long getSize() {
    return size;
  }

  public int getReferenceCount() {
    return referenceCount;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
