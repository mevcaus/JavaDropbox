package com.javadropbox.javadropbox.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * An account. Each one has files of its own, in its own folder of the store (see
 * StoragePaths#home), which nobody else can see.
 */
@Entity
@Table(
    name = "users",
    uniqueConstraints = @UniqueConstraint(name = "uk_users_username", columnNames = "username"))
public class User {

  /** Manages the other accounts and sees the server's metrics, besides having files. */
  public static final String ROLE_ADMIN = "ROLE_ADMIN";

  /** Has files, and nothing more. */
  public static final String ROLE_USER = "ROLE_USER";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String username;

  private String password;

  /** {@link #ROLE_ADMIN} or {@link #ROLE_USER}. */
  @Column(nullable = false)
  private String role;

  /** A disabled account cannot sign in, and its share links stop opening. */
  @Column(nullable = false)
  private boolean enabled = true;

  /** The most the account may store, previous versions included; null for no limit. */
  private Long quotaBytes;

  /**
   * Recorded in each session at sign-in and bumped whenever the account changes in a way that has
   * to end its sessions: disabled, its role changed or its password reset. A session holding an
   * older value is signed out on its next request.
   */
  @Column(nullable = false)
  private int sessionVersion;

  public User() {}

  public User(String username, String password, String role) {
    this.username = username;
    this.password = password;
    this.role = role;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }

  public boolean isAdmin() {
    return ROLE_ADMIN.equals(role);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Long getQuotaBytes() {
    return quotaBytes;
  }

  public void setQuotaBytes(Long quotaBytes) {
    this.quotaBytes = quotaBytes;
  }

  public int getSessionVersion() {
    return sessionVersion;
  }

  /** Ends every session the account has; see {@link #getSessionVersion}. */
  public void endSessions() {
    sessionVersion++;
  }
}
