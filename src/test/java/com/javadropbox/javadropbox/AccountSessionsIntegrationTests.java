package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.AccountSessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ending an account's sessions deletes them from spring_session, all of that account's and nobody
 * else's, once the change that ends them commits: a change that rolls back signs nobody out.
 */
@SpringBootTest
@DisplayName("Ending an account's sessions")
class AccountSessionsIntegrationTests {

  @Autowired private AccountSessions accountSessions;
  @Autowired private JdbcIndexedSessionRepository sessionRepository;
  @Autowired private UserRepository users;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  private User ada;

  @BeforeEach
  void setUp() {
    ada = users.save(new User("ada", "unused", User.ROLE_USER));
    users.save(new User("bob", "unused", User.ROLE_USER));
    signIn("ada");
    signIn("ada");
    signIn("bob");
  }

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  @Test
  @DisplayName("deletes every session of the account, and no other, once the change commits")
  void deletesTheAccountsSessionsOnCommit() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              accountSessions.end(ada);
              assertThat(sessionsOf("ada")).isEqualTo(2);
            });

    assertThat(sessionsOf("ada")).isZero();
    assertThat(sessionsOf("bob")).isEqualTo(1);
  }

  @Test
  @DisplayName("leaves them alone if the change rolls back")
  void keepsThemOnRollback() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              accountSessions.end(ada);
              status.setRollbackOnly();
            });

    assertThat(sessionsOf("ada")).isEqualTo(2);
  }

  @Test
  @DisplayName("bumps the session version as well")
  void bumpsTheSessionVersion() {
    int before = ada.getSessionVersion();

    accountSessions.end(ada);

    assertThat(ada.getSessionVersion()).isGreaterThan(before);
    assertThat(sessionsOf("ada")).isZero();
  }

  // A session as signing in leaves one: indexed by the account's username. Raw, because the type
  // of session the JDBC repository saves is not public.
  @SuppressWarnings({"rawtypes", "unchecked"})
  private void signIn(String username) {
    SessionRepository repository = sessionRepository;
    Session session = repository.createSession();
    session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, username);
    repository.save(session);
  }

  private int sessionsOf(String username) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM spring_session WHERE principal_name = ?", Integer.class, username);
  }
}
