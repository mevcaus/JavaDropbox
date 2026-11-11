package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ends every session an account has when it is disabled, its role changes or its password is reset,
 * and makes sure no session signs in with the account as it was before.
 *
 * <p>Ending them deletes them from spring_session, found by the username they signed in with
 * (Spring Session's principal_name index), as part of the change's own transaction: a change that
 * fails signs nobody out, and one that commits leaves no session behind. Spring Session's own
 * repository could not do that, as it deletes in a transaction of its own.
 *
 * <p>That leaves a session that signs in while the change is being made: its password was checked
 * against the account as it was, and its row may not exist yet when the change deletes the others.
 * So the change bumps the account's session version, and every sign-in reads the version again once
 * its session is stored ({@link #isCurrent}), refusing the sign-in if it moved. The two take their
 * steps in opposite orders, so one of them always sees the other:
 *
 * <ul>
 *   <li>The change writes the new version first, which locks the account's row, and deletes the
 *       sessions after.
 *   <li>The sign-in stores its session first (spring.session.jdbc.flush-mode=immediate commits it
 *       when the sign-in is put in the session), then reads the version, waiting for that lock.
 * </ul>
 *
 * If the sign-in reads first, its session was there to be deleted. If the change writes first, the
 * sign-in waits for it to commit and sees the new version. Either way nothing has to be checked on
 * every request.
 */
@Component
public class AccountSessions {

  private static final Logger log = LoggerFactory.getLogger(AccountSessions.class);

  private final JdbcTemplate jdbc;
  private final UserRepository users;

  public AccountSessions(JdbcTemplate jdbc, UserRepository users) {
    this.jdbc = jdbc;
    this.users = users;
  }

  /** Ends the account's sessions as part of the current transaction, which there must be. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void end(User account) {
    account.bumpSessionVersion();
    // Writes the new version (and whatever else the change set) now, taking the row lock that a
    // sign-in's isCurrent waits for, before any session is deleted.
    users.saveAndFlush(account);
    // The table V9 creates for Spring Session (see SessionConfig); their attributes go with them
    // (ON DELETE CASCADE).
    int ended =
        jdbc.update("DELETE FROM spring_session WHERE principal_name = ?", account.getUsername());
    log.info("Ending {} session(s) of \"{}\"", ended, account.getUsername());
  }

  /**
   * Whether a sign-in whose password was checked against {@code sessionVersion} of the account
   * still has the account as it is. Call it only once the new session is stored. Waits for a change
   * to the account that is under way.
   */
  public boolean isCurrent(String username, int sessionVersion) {
    Integer current =
        jdbc.query(
            "SELECT session_version FROM users WHERE username = ? FOR SHARE",
            rs -> rs.next() ? rs.getInt(1) : null,
            username);
    return current != null && current == sessionVersion;
  }
}
