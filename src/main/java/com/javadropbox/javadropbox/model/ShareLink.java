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

/**
 * A public link to one file or folder. The URL carries a random token; only its hash is stored, so
 * the table cannot be used to rebuild a working link.
 */
@Entity
@Table(
    name = "share_links",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_share_links_token_hash", columnNames = "token_hash"))
public class ShareLink {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** SHA-256 of the token in the URL, hex-encoded. */
  @Column(nullable = false, length = 64)
  private String tokenHash;

  // A link belongs to the item it was made for, not to its path: the database removes it with the
  // item, so a different item created at the same path later is not served by it.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "file_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private FileMetadata file;

  /** The item's path when the link was made. */
  @Column(nullable = false, length = 4096)
  private String path;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant expiresAt;

  private Instant revokedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "created_by")
  private User createdBy;

  protected ShareLink() {}

  public ShareLink(
      String tokenHash, FileMetadata file, Instant createdAt, Instant expiresAt, User createdBy) {
    this.tokenHash = tokenHash;
    this.file = file;
    this.path = file.getPath();
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.createdBy = createdBy;
  }

  /** Whether the link still opens: not revoked and not expired. */
  public boolean isActive(Instant now) {
    return revokedAt == null && now.isBefore(expiresAt);
  }

  public void revoke(Instant now) {
    if (revokedAt == null) {
      revokedAt = now;
    }
  }

  public Long getId() {
    return id;
  }

  public FileMetadata getFile() {
    return file;
  }

  public String getPath() {
    return path;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public User getCreatedBy() {
    return createdBy;
  }
}
