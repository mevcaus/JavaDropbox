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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * A one-time link an admin hands someone so they can choose a password: an invitation to create an
 * account, or a reset of an existing account's password. Like a share link, the URL carries a
 * random token and only its hash is stored. Using the link deletes it.
 */
@Entity
@Table(
    name = "account_links",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_account_links_token_hash", columnNames = "token_hash"))
public class AccountLink {

  public enum Purpose {
    INVITE,
    PASSWORD_RESET
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** SHA-256 of the token in the URL, hex-encoded. */
  @Column(nullable = false, length = 64)
  private String tokenHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private Purpose purpose;

  /** An invitation's: the account it creates. */
  private String username;

  /** An invitation's: the new account's role. */
  private String role;

  /** An invitation's: the new account's quota, null for none. */
  private Long quotaBytes;

  /** A password reset's: the account whose password it sets. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id")
  @OnDelete(action = OnDeleteAction.CASCADE)
  private User user;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "created_by")
  private User createdBy;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant expiresAt;

  protected AccountLink() {}

  private AccountLink(
      String tokenHash, Purpose purpose, User createdBy, Instant createdAt, Instant expiresAt) {
    this.tokenHash = tokenHash;
    this.purpose = purpose;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  public static AccountLink invite(
      String tokenHash,
      String username,
      String role,
      Long quotaBytes,
      User createdBy,
      Instant createdAt,
      Instant expiresAt) {
    AccountLink link = new AccountLink(tokenHash, Purpose.INVITE, createdBy, createdAt, expiresAt);
    link.username = username;
    link.role = role;
    link.quotaBytes = quotaBytes;
    return link;
  }

  public static AccountLink passwordReset(
      String tokenHash, User user, User createdBy, Instant createdAt, Instant expiresAt) {
    AccountLink link =
        new AccountLink(tokenHash, Purpose.PASSWORD_RESET, createdBy, createdAt, expiresAt);
    link.user = user;
    return link;
  }

  public boolean isActive(Instant now) {
    return expiresAt.isAfter(now);
  }

  public Long getId() {
    return id;
  }

  public Purpose getPurpose() {
    return purpose;
  }

  public String getUsername() {
    return username;
  }

  public String getRole() {
    return role;
  }

  public Long getQuotaBytes() {
    return quotaBytes;
  }

  public User getUser() {
    return user;
  }

  public User getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }
}
