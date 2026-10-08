package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.AccountDto;
import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.exception.ConflictException;
import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.AccountLinkRepository;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

/**
 * What admins do to accounts: list them with what they store, disable and enable them, and change
 * their role and quota. New accounts come from invitations ({@link AccountLinkService}). Accounts
 * are never deleted, so their files and history are never orphaned.
 *
 * <p>An admin cannot disable their own account or change its role, and there is always at least one
 * admin who can sign in, so nobody can lock everyone out of managing the accounts.
 */
@Service
public class AccountService {

  private static final Logger log = LoggerFactory.getLogger(AccountService.class);

  private final UserRepository users;
  private final AccountLinkRepository links;
  private final AuthService authService;
  private final StorageQuota quota;

  public AccountService(
      UserRepository users,
      AccountLinkRepository links,
      AuthService authService,
      StorageQuota quota) {
    this.users = users;
    this.links = links;
    this.authService = authService;
    this.quota = quota;
  }

  /** Every account, by username, with what it stores. */
  public List<AccountDto> list() throws IOException {
    List<AccountDto> accounts = new ArrayList<>();
    for (User user : users.findAllByOrderByUsernameAsc()) {
      accounts.add(
          new AccountDto(
              user.getId(),
              user.getUsername(),
              Roles.name(user.getRole()),
              user.isEnabled(),
              user.getQuotaBytes(),
              quota.usedBytes(user)));
    }
    return accounts;
  }

  /**
   * Lets an account sign in again, or stops it: a disabled account's sessions end at once, and its
   * share links stop opening until it is enabled again. The invitations and password reset links it
   * made as an admin are withdrawn for good: an account is disabled when whoever has it should no
   * longer get in, and those links would let them make accounts or take others over.
   *
   * @throws ConflictException for the admin's own account, or the last admin who can sign in
   */
  @Transactional
  public void setEnabled(Long id, boolean enabled) {
    User account = otherAccount(id, "disable or enable");
    if (account.isEnabled() == enabled) {
      return;
    }
    if (!enabled && account.isAdmin()) {
      keepAnotherAdmin(account);
    }
    account.setEnabled(enabled);
    account.endSessions();
    int withdrawn = enabled ? 0 : links.deleteCreatedBy(account);
    log.info(
        "{} {} the account \"{}\"{}",
        authService.requireCurrentUser().getUsername(),
        enabled ? "enabled" : "disabled",
        account.getUsername(),
        withdrawn > 0 ? ", withdrawing the " + withdrawn + " links it made" : "");
  }

  /**
   * Makes an account an admin or takes that away. Its sessions end, so it signs in again with the
   * new role.
   *
   * @param role {@code ADMIN} or {@code USER}
   * @throws ConflictException for the admin's own account, or the last admin who can sign in
   */
  @Transactional
  public void setRole(Long id, String role) {
    String newRole = Roles.parse(role);
    User account = otherAccount(id, "change the role of");
    if (account.getRole().equals(newRole)) {
      return;
    }
    if (account.isAdmin()) {
      keepAnotherAdmin(account);
    }
    account.setRole(newRole);
    account.endSessions();
    log.info(
        "{} made \"{}\" {}",
        authService.requireCurrentUser().getUsername(),
        account.getUsername(),
        Roles.name(newRole));
  }

  /**
   * Sets the most an account may store, previous versions included. One below what it stores now is
   * allowed: nothing more can be stored until enough is deleted.
   *
   * @param quota a size such as {@code 5GB}, {@code 500MB} or a number of bytes; empty for no limit
   */
  @Transactional
  public void setQuota(Long id, String quota) {
    User account = users.findById(id).orElseThrow(AccountService::accountNotFound);
    account.setQuotaBytes(parseQuota(quota));
  }

  /**
   * A quota as the API takes it, in bytes; null for none.
   *
   * @throws BadRequestException if it is not a size, or not more than 0
   */
  static Long parseQuota(String quota) {
    if (quota == null || quota.isBlank()) {
      return null;
    }
    DataSize size;
    try {
      size = DataSize.parse(quota.trim());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("quota must be a size such as 500MB or 5GB");
    }
    if (size.toBytes() <= 0) {
      throw new BadRequestException("quota must be more than 0");
    }
    return size.toBytes();
  }

  private User otherAccount(Long id, String action) {
    User account = users.findById(id).orElseThrow(AccountService::accountNotFound);
    if (account.getId().equals(authService.requireCurrentUser().getId())) {
      throw new ConflictException("You can't " + action + " your own account");
    }
    return account;
  }

  // Locks the admins who can sign in, so that two admins taking each other's rights away at once
  // take turns, and the second finds out it would leave nobody.
  private void keepAnotherAdmin(User account) {
    boolean another =
        users.lockEnabledAdmins().stream()
            .anyMatch(admin -> !admin.getId().equals(account.getId()));
    if (!another) {
      throw new ConflictException("There has to be at least one admin who can sign in");
    }
  }

  static NotFoundException accountNotFound() {
    return new NotFoundException("Account not found");
  }
}
