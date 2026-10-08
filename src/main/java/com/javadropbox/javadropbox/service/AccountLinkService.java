package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.AccountLinkInfo;
import com.javadropbox.javadropbox.dto.InviteDto;
import com.javadropbox.javadropbox.exception.ConflictException;
import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.model.AccountLink;
import com.javadropbox.javadropbox.model.AccountLink.Purpose;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.AccountLinkRepository;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time links an admin makes for someone to choose a password with: an invitation, which creates
 * an account when it is used, or a password reset for an existing account. The admin hands the link
 * over however they like; it is shown once, when it is made, and only a hash of its token is
 * stored. Using a link deletes it, and making a new one for the same username or account replaces
 * the old.
 */
@Service
public class AccountLinkService {

  static final Duration INVITE_LIFETIME = Duration.ofDays(7);
  static final Duration PASSWORD_RESET_LIFETIME = Duration.ofDays(1);

  private static final Logger log = LoggerFactory.getLogger(AccountLinkService.class);

  private final AccountLinkRepository links;
  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final AuthService authService;

  public AccountLinkService(
      AccountLinkRepository links,
      UserRepository users,
      PasswordEncoder passwordEncoder,
      AuthService authService) {
    this.links = links;
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.authService = authService;
  }

  /** A newly made link. The token is only ever available here, when the link is made. */
  public record CreatedLink(String token, Instant expiresAt) {}

  /**
   * Invites someone to create the account {@code username}, replacing any earlier invitation for
   * that name.
   *
   * @param role {@code ADMIN} or {@code USER}
   * @param quota the account's quota, as {@link AccountService#setQuota} takes it
   * @throws ConflictException if an account already has the username
   */
  @Transactional
  public CreatedLink invite(String username, String role, String quota) {
    String name = Credentials.username(username);
    String newRole = Roles.parse(role);
    Long quotaBytes = AccountService.parseQuota(quota);
    if (users.existsByUsername(name)) {
      throw new ConflictException("There is already an account called \"" + name + "\"");
    }

    Instant now = Instant.now();
    links.deleteExpiredBefore(now);
    links.deleteInvite(name);
    String token = Tokens.newToken();
    Instant expiresAt = now.plus(INVITE_LIFETIME);
    User admin = authService.requireCurrentUser();
    links.save(
        AccountLink.invite(Tokens.hash(token), name, newRole, quotaBytes, admin, now, expiresAt));
    log.info("{} invited \"{}\" as {}", admin.getUsername(), name, Roles.name(newRole));
    return new CreatedLink(token, expiresAt);
  }

  /** The invitations that have not been used or expired, the newest first. */
  @Transactional(readOnly = true)
  public List<InviteDto> openInvites() {
    return links.findOpenInvites(Instant.now()).stream()
        .map(
            link ->
                new InviteDto(
                    link.getId(),
                    link.getUsername(),
                    Roles.name(link.getRole()),
                    link.getQuotaBytes(),
                    link.getCreatedAt(),
                    link.getExpiresAt(),
                    link.getCreatedBy() != null ? link.getCreatedBy().getUsername() : null))
        .toList();
  }

  /**
   * Withdraws an invitation, so its link no longer works.
   *
   * @throws NotFoundException if there is no such invitation
   */
  @Transactional
  public void revokeInvite(Long id) {
    AccountLink invite =
        links
            .findById(id)
            .filter(link -> link.getPurpose() == Purpose.INVITE)
            .orElseThrow(() -> new NotFoundException("Invitation not found"));
    links.delete(invite);
  }

  /**
   * Makes a link that sets a new password for an account, replacing any earlier one. The account's
   * current password keeps working until the link is used.
   *
   * @throws NotFoundException if there is no such account
   */
  @Transactional
  public CreatedLink passwordReset(Long userId) {
    User account = users.findById(userId).orElseThrow(AccountService::accountNotFound);
    Instant now = Instant.now();
    links.deleteExpiredBefore(now);
    links.deletePasswordReset(account);
    String token = Tokens.newToken();
    Instant expiresAt = now.plus(PASSWORD_RESET_LIFETIME);
    User admin = authService.requireCurrentUser();
    links.save(AccountLink.passwordReset(Tokens.hash(token), account, admin, now, expiresAt));
    log.info(
        "{} made a password reset link for \"{}\"", admin.getUsername(), account.getUsername());
    return new CreatedLink(token, expiresAt);
  }

  /**
   * What a link is for, for the page that uses it.
   *
   * @throws NotFoundException if the token is unknown, expired, or for the other purpose
   */
  @Transactional(readOnly = true)
  public AccountLinkInfo describe(Purpose purpose, String token) {
    AccountLink link = find(purpose, token);
    return new AccountLinkInfo(username(link), link.getExpiresAt());
  }

  /**
   * Uses a link: creates the invited account with {@code password}, or sets it as the account's new
   * password and ends the account's sessions. Either way the link is used up.
   *
   * @throws NotFoundException as for {@link #describe}
   * @throws ConflictException if an account took the invited username in the meantime
   */
  @Transactional
  public void redeem(Purpose purpose, String token, String password) {
    AccountLink link = find(purpose, token);
    Credentials.checkPassword(password);

    if (purpose == Purpose.INVITE) {
      if (users.existsByUsername(link.getUsername())) {
        // A new invitation for the same username would be refused too.
        throw new ConflictException(
            "There is already an account called \""
                + link.getUsername()
                + "\". Ask for an invitation with another username.");
      }
      User account = new User(link.getUsername(), passwordEncoder.encode(password), link.getRole());
      account.setQuotaBytes(link.getQuotaBytes());
      users.save(account);
      log.info("Created the account \"{}\" from an invitation", account.getUsername());
    } else {
      User account = link.getUser();
      account.setPassword(passwordEncoder.encode(password));
      account.endSessions();
      log.info("Set a new password for \"{}\" from a reset link", account.getUsername());
    }
    links.delete(link);
  }

  private AccountLink find(Purpose purpose, String token) {
    return links
        .findByTokenHash(Tokens.hash(token))
        .filter(link -> link.getPurpose() == purpose && link.isActive(Instant.now()))
        .orElseThrow(() -> new NotFoundException("This link has expired or has already been used"));
  }

  private static String username(AccountLink link) {
    return link.getPurpose() == Purpose.INVITE ? link.getUsername() : link.getUser().getUsername();
  }
}
