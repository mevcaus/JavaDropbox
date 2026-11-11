package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ending an account's sessions deletes them from spring_session, all of that account's and nobody
 * else's, as part of the change that ends them: a change that rolls back signs nobody out.
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
  @DisplayName("deletes every session of the account, and no other, and bumps its version")
  void deletesTheAccountsSessions() {
    int before = ada.getSessionVersion();

    inTransaction(() -> accountSessions.end(ada));

    assertThat(TestDatabase.sessionsOf(jdbc, "ada")).isZero();
    assertThat(TestDatabase.sessionsOf(jdbc, "bob")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session_attributes", Integer.class))
        .isEqualTo(1);
    assertThat(ada.getSessionVersion()).isGreaterThan(before);
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

    assertThat(TestDatabase.sessionsOf(jdbc, "ada")).isEqualTo(2);
  }

  // Outside a transaction the sessions would be gone even if the change then failed.
  @Test
  @DisplayName("refuses to run outside a transaction")
  void needsATransaction() {
    assertThatThrownBy(() -> accountSessions.end(ada))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(TestDatabase.sessionsOf(jdbc, "ada")).isEqualTo(2);
  }

  private void inTransaction(Runnable work) {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
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
}
