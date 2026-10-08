package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.model.User;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * The account a sign-in checks its password against, as SecurityConfig's UserDetailsService loads
 * it, along with the account's session version from the same read. AccountSessionFilter records
 * that version in the new session rather than reading it again once the password has been checked:
 * a change that ends the account's sessions while the password is being checked (it is disabled,
 * changes role or has its password reset) then still ends this one on its next request.
 */
class AccountDetails extends org.springframework.security.core.userdetails.User {

  private static final long serialVersionUID = 1L;

  private final int sessionVersion;

  AccountDetails(User account) {
    super(
        account.getUsername(),
        account.getPassword(),
        account.isEnabled(),
        true,
        true,
        true,
        AuthorityUtils.createAuthorityList(account.getRole()));
    this.sessionVersion = account.getSessionVersion();
  }

  int sessionVersion() {
    return sessionVersion;
  }
}
