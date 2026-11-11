package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ends every session an account has, when it is disabled, its role changes or its password is
 * reset: they are deleted from spring_session, found by the username they signed in with (Spring
 * Session's principal_name index), so none of them makes another request as the account was.
 *
 * <p>The deletion is part of the change's own transaction, so the two commit or roll back together:
 * a change that fails signs nobody out, and one that commits has no sessions left behind. Spring
 * Session's own repository could not do that: it deletes in a transaction of its own. The account's
 * session version is bumped too, which ends a session signed in while the change was being made
 * (see {@link User#getSessionVersion}).
 */
@Component
public class AccountSessions {

  private static final Logger log = LoggerFactory.getLogger(AccountSessions.class);

  private final JdbcTemplate jdbc;

  public AccountSessions(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Ends the account's sessions as part of the current transaction, which there must be. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void end(User account) {
    account.bumpSessionVersion();
    // The table V9 creates for Spring Session (see SessionConfig); their attributes go with them
    // (ON DELETE CASCADE).
    int ended =
        jdbc.update("DELETE FROM spring_session WHERE principal_name = ?", account.getUsername());
    log.info("Ending {} session(s) of \"{}\"", ended, account.getUsername());
  }
}
