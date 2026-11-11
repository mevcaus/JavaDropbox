package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Ends every session an account has, when it is disabled, its role changes or its password is
 * reset: they are deleted from the session store (spring_session), found by the username they
 * signed in with, so none of them makes another request as the account was.
 *
 * <p>They are deleted once the change commits. Spring Session deletes in a transaction of its own,
 * so deleting them before then would sign the account out even if the change rolled back. Until the
 * deletion, and should it fail, the account's bumped session version ends them on their next
 * request instead (see {@link User#getSessionVersion}).
 */
@Component
public class AccountSessions {

  private static final Logger log = LoggerFactory.getLogger(AccountSessions.class);

  private final FindByIndexNameSessionRepository<? extends Session> sessions;

  public AccountSessions(FindByIndexNameSessionRepository<? extends Session> sessions) {
    this.sessions = sessions;
  }

  /** Ends the account's sessions, once the current transaction commits (at once outside one). */
  public void end(User account) {
    account.endSessions();
    String username = account.getUsername();
    AfterCommit.run("end the sessions of \"" + username + "\"", () -> delete(username));
  }

  private void delete(String username) {
    var ended = sessions.findByPrincipalName(username).keySet();
    ended.forEach(sessions::deleteById);
    if (!ended.isEmpty()) {
      log.info("Ended {} session(s) of \"{}\"", ended.size(), username);
    }
  }
}
