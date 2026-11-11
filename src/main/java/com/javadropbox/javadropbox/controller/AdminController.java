package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.AccountDto;
import com.javadropbox.javadropbox.dto.ImportResult;
import com.javadropbox.javadropbox.dto.InviteDto;
import com.javadropbox.javadropbox.dto.Message;
import com.javadropbox.javadropbox.dto.NewLink;
import com.javadropbox.javadropbox.service.AccountLinkService;
import com.javadropbox.javadropbox.service.AccountLinkService.CreatedLink;
import com.javadropbox.javadropbox.service.AccountService;
import com.javadropbox.javadropbox.service.ImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Managing accounts. Everything under {@code /api/admin} needs the {@code ADMIN} role (see
 * SecurityConfig); admins never see other accounts' files, only what they store in all.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "Manage accounts: invite people, disable them, set quotas")
public class AdminController {

  private final AccountService accounts;
  private final AccountLinkService accountLinks;
  private final ImportService imports;

  public AdminController(
      AccountService accounts, AccountLinkService accountLinks, ImportService imports) {
    this.accounts = accounts;
    this.accountLinks = accountLinks;
    this.imports = imports;
  }

  @GetMapping("/users")
  @Operation(
      operationId = "listAccounts",
      summary = "List accounts",
      description =
          "Every account by username, with its role, whether it can sign in, its quota and what it"
              + " stores, previous versions included.")
  public List<AccountDto> listAccounts() throws IOException {
    return accounts.list();
  }

  @PutMapping(path = "/users/{id}/enabled", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  @Operation(
      operationId = "setAccountEnabled",
      summary = "Disable or enable an account",
      description =
          "A disabled account cannot sign in, its sessions end at once, and its share links stop"
              + " opening until it is enabled again. The invitations and password reset links it"
              + " made are withdrawn for good. Not for your own account, or the last admin who can"
              + " sign in (409).")
  public Message setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
    accounts.setEnabled(id, enabled);
    return new Message(enabled ? "Account enabled" : "Account disabled");
  }

  @PutMapping(path = "/users/{id}/role", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  @Operation(
      operationId = "setAccountRole",
      summary = "Change an account's role",
      description =
          "ADMIN or USER. The account's sessions end, so it signs in again with the new role. Not"
              + " for your own account, or the last admin who can sign in (409).")
  public Message setRole(@PathVariable Long id, @RequestParam String role) {
    accounts.setRole(id, role);
    return new Message("Role changed");
  }

  @PutMapping(path = "/users/{id}/quota", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  @Operation(
      operationId = "setAccountQuota",
      summary = "Set an account's quota",
      description =
          "The most the account may store, previous versions included, as a size such as 500MB"
              + " or 5GB, or a number of bytes. Empty for no limit. Uploads and restores that would"
              + " go over it are refused with 507.")
  public Message setQuota(@PathVariable Long id, @RequestParam(defaultValue = "") String quota) {
    accounts.setQuota(id, quota);
    return new Message("Quota changed");
  }

  @PostMapping("/users/{id}/password-reset")
  @Operation(
      operationId = "createPasswordResetLink",
      summary = "Make a password reset link",
      description =
          "A one-time link, valid for a day, at which the account's user chooses a new password;"
              + " using it ends the account's sessions. The URL is only returned here, and a new"
              + " link replaces the previous one.")
  public NewLink resetPassword(@PathVariable Long id, HttpServletRequest request) {
    return linkResponse(accountLinks.passwordReset(id), "/reset-password/", request);
  }

  @PostMapping("/users/{id}/import")
  @Operation(
      operationId = "importFiles",
      summary = "Import the files added to an account's folder outside the app",
      description =
          "Gives every file and folder in the account's folder that has no row one, owned by the"
              + " account, so it has an id like anything uploaded. Never removes anything: rows"
              + " whose file is missing from the store are only counted, and items whose names the app"
              + " wouldn't accept are left out and counted. Returns the counts, not the files. 409 when none of the account's files are in the store, which looks"
              + " like the wrong serving directory or bucket.")
  public ImportResult importFiles(@PathVariable Long id, Principal admin) throws IOException {
    return imports.importFiles(id, admin.getName());
  }

  @GetMapping("/invites")
  @Operation(
      operationId = "listInvites",
      summary = "List invitations",
      description = "Invitations that have not been used or expired yet, newest first.")
  public List<InviteDto> listInvites() {
    return accountLinks.openInvites();
  }

  @PostMapping(path = "/invites", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  @Operation(
      operationId = "createInvite",
      summary = "Invite someone",
      description =
          "A one-time link, valid for 7 days, at which someone chooses a password for a new"
              + " account with this username, role (ADMIN or USER, USER if left out) and quota"
              + " (as for the quota endpoint; none if left out). The URL is only returned here. A"
              + " new invitation for the same username replaces the previous one; a username an"
              + " account already has is a 409.")
  public NewLink invite(
      @RequestParam String username,
      @RequestParam(defaultValue = "USER") String role,
      @RequestParam(defaultValue = "") String quota,
      HttpServletRequest request) {
    return linkResponse(accountLinks.invite(username, role, quota), "/invite/", request);
  }

  @DeleteMapping("/invites/{id}")
  @Operation(
      operationId = "revokeInvite",
      summary = "Withdraw an invitation",
      description = "Its link stops working at once.")
  public Message revokeInvite(@PathVariable Long id) {
    accountLinks.revokeInvite(id);
    return new Message("Invitation withdrawn");
  }

  private static NewLink linkResponse(CreatedLink link, String route, HttpServletRequest request) {
    String url =
        ServletUriComponentsBuilder.fromRequestUri(request)
            .replacePath(route + link.token())
            .replaceQuery(null)
            .build()
            .toUriString();
    return new NewLink(url, link.expiresAt());
  }
}
