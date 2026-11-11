package com.javadropbox.javadropbox.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

@DisplayName("Session config")
class SessionConfigTests {

  // The sign-in has worked by the time the cleanup runs: a failure there must not refuse it.
  @Test
  @DisplayName("a failed cleanup at sign-in is only logged")
  void failedCleanupDoesNotFailTheSignIn() {
    JdbcIndexedSessionRepository repository = mock(JdbcIndexedSessionRepository.class);
    doThrow(new DataAccessResourceFailureException("the database went away"))
        .when(repository)
        .cleanUpExpiredSessions();

    assertThatCode(() -> SessionConfig.deleteExpiredSessions(repository))
        .doesNotThrowAnyException();
    verify(repository).cleanUpExpiredSessions();
  }
}
