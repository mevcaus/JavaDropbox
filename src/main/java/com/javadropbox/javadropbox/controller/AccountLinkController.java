package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.AccountLinkInfo;
import com.javadropbox.javadropbox.model.AccountLink.Purpose;
import com.javadropbox.javadropbox.service.AccountLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The links admins hand out (see AdminController), used by someone without a session: they are
 * public in SecurityConfig. A browser opening {@code /invite/{token}} or {@code
 * /reset-password/{token}} gets the app's page for it, which reads {@code /info} and posts the
 * chosen password back.
 */
@RestController
@Tag(name = "Account links", description = "Accept an invitation or reset a password")
public class AccountLinkController {

  private final AccountLinkService accountLinks;

  public AccountLinkController(AccountLinkService accountLinks) {
    this.accountLinks = accountLinks;
  }

  @GetMapping("/invite/{token}/info")
  @Operation(
      summary = "Describe an invitation",
      description =
          "The username the invitation creates and when it expires. 404 once it has been used,"
              + " withdrawn or expired. Publicly accessible.")
  public AccountLinkInfo describeInvite(@PathVariable String token) {
    return accountLinks.describe(Purpose.INVITE, token);
  }

  @PostMapping("/invite/{token}")
  @Operation(
      summary = "Accept an invitation",
      description =
          "Creates the invited account with this password (at least 8 characters), then the"
              + " invitation is used up. Publicly accessible.")
  public Map<String, String> acceptInvite(
      @PathVariable String token, @RequestParam(required = false) String password) {
    accountLinks.redeem(Purpose.INVITE, token, password);
    return Map.of("message", "Account created");
  }

  @GetMapping("/reset-password/{token}/info")
  @Operation(
      summary = "Describe a password reset link",
      description =
          "The account whose password the link sets and when it expires. 404 once it has been"
              + " used or expired. Publicly accessible.")
  public AccountLinkInfo describePasswordReset(@PathVariable String token) {
    return accountLinks.describe(Purpose.PASSWORD_RESET, token);
  }

  @PostMapping("/reset-password/{token}")
  @Operation(
      summary = "Reset a password",
      description =
          "Sets the account's new password (at least 8 characters) and ends its sessions, then the"
              + " link is used up. Publicly accessible.")
  public Map<String, String> resetPassword(
      @PathVariable String token, @RequestParam(required = false) String password) {
    accountLinks.redeem(Purpose.PASSWORD_RESET, token, password);
    return Map.of("message", "Password changed");
  }
}
