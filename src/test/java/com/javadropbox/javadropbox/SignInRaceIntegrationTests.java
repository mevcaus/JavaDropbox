package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.AccountSessions;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An admin changes an account (here, makes it a user) while someone is signing in to it. Sessions
 * are not checked against their account on every request, so a sign-in must never come out of that
 * holding the account as it was: either the change deletes its session, or the sign-in is refused.
 * The changes are made from another thread, with a connection of their own, as another request's
 * would be.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Signing in while the account changes")
@TempServingDirectory
class SignInRaceIntegrationTests {

  @LocalServerPort private int port;

  @MockitoSpyBean private PasswordEncoder passwordEncoder;
  @MockitoSpyBean private AccountSessions accountSessions;
  @Autowired private UserRepository users;
  @MockitoSpyBean private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  private HttpTestClient http;

  @BeforeEach
  void setUp() {
    http = new HttpTestClient(port);
    users.save(new User("ada", passwordEncoder.encode("correct-horse"), User.ROLE_ADMIN));
    // The other admin, who makes the change.
    users.save(new User("root", "unused", User.ROLE_ADMIN));
  }

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  @Test
  @DisplayName("a change while the password is checked refuses the sign-in")
  void changeDuringThePasswordCheck() throws Exception {
    doAnswer(
            call -> {
              boolean matches = (boolean) call.callRealMethod();
              demoteInAnotherRequest();
              return matches;
            })
        .when(passwordEncoder)
        .matches(any(), anyString());

    assertThat(http.login("ada", "correct-horse", Map.of()).statusCode()).isEqualTo(401);
    assertThat(TestDatabase.sessionsOf(jdbc, "ada")).isZero();
  }

  @Test
  @DisplayName("a change between storing the session and checking it refuses the sign-in")
  void changeBeforeTheCheck() throws Exception {
    CompletableFuture<Integer> storedBeforeTheCheck = new CompletableFuture<>();
    doAnswer(
            call -> {
              storedBeforeTheCheck.complete(TestDatabase.sessionsOf(jdbc, "ada"));
              demoteInAnotherRequest();
              return call.callRealMethod();
            })
        .when(accountSessions)
        .isCurrent(anyString(), anyInt());

    assertThat(http.login("ada", "correct-horse", Map.of()).statusCode()).isEqualTo(401);
    // SessionConfig flushes sessions at once: the session was there for the change to delete.
    assertThat(storedBeforeTheCheck.get()).isEqualTo(1);
    assertThat(TestDatabase.sessionsOf(jdbc, "ada")).isZero();
  }

  @Test
  @DisplayName("a change after the check deletes the session the sign-in stored")
  void changeAfterTheCheck() throws Exception {
    doAnswer(
            call -> {
              boolean current = (boolean) call.callRealMethod();
              demoteInAnotherRequest();
              return current;
            })
        .when(accountSessions)
        .isCurrent(anyString(), anyInt());

    HttpResponse<String> login = http.login("ada", "correct-horse", Map.of());

    assertThat(login.statusCode()).isEqualTo(200);
    assertThat(TestDatabase.sessionsOf(jdbc, "ada")).isZero();
    String session = HttpTestClient.cookieValue(login, "JSESSIONID");
    assertThat(http.get("/api/me", Map.of("Cookie", "JSESSIONID=" + session)).statusCode())
        .isEqualTo(401);
  }

  // Fails closed: a sign-in that could not be checked keeps no session.
  @Test
  @DisplayName("a check that fails ends the session it was checking")
  void failedCheckEndsTheSession() {
    doThrow(new IllegalStateException("the database went away"))
        .when(accountSessions)
        .isCurrent(anyString(), anyInt());

    HttpResponse<String> login = http.login("ada", "correct-horse", Map.of());

    assertThat(login.statusCode()).isEqualTo(500);
    assertThat(TestDatabase.sessionsOf(jdbc, "ada")).isZero();
  }

  // A change writes the new version before it deletes the sessions, so a sign-in that comes between
  // the two waits for it; the other way round, its session could be stored after the deletion and
  // checked before the new version was written.
  @Test
  @DisplayName("a sign-in between a change's new version and its deletion waits, and is refused")
  void signInBetweenTheVersionAndTheDeletion() throws Exception {
    CountDownLatch deleting = new CountDownLatch(1);
    CountDownLatch delete = new CountDownLatch(1);
    doAnswer(
            call -> {
              deleting.countDown();
              await(delete);
              return call.callRealMethod();
            })
        .when(jdbc)
        .update(startsWith("DELETE FROM spring_session"), ArgumentMatchers.<Object>any());
    CompletableFuture<Void> change = CompletableFuture.runAsync(() -> inTransaction(this::demote));
    assertThat(deleting.await(10, TimeUnit.SECONDS)).isTrue();

    CompletableFuture<HttpResponse<String>> login =
        CompletableFuture.supplyAsync(() -> http.login("ada", "correct-horse", Map.of()));
    Thread.sleep(500);
    assertThat(login).isNotDone();

    delete.countDown();
    change.get(10, TimeUnit.SECONDS);
    assertThat(login.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(401);
    assertThat(TestDatabase.sessionsOf(jdbc, "ada")).isZero();
  }

  // The change has written the new version but not yet committed when the sign-in checks it.
  @Test
  @DisplayName("a check waits for a change under way to commit, and then refuses the sign-in")
  void checkWaitsForAChangeUnderWay() throws Exception {
    int version = users.findByUsername("ada").orElseThrow().getSessionVersion();
    CountDownLatch written = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    CompletableFuture<Void> change =
        CompletableFuture.runAsync(
            () ->
                inTransaction(
                    () -> {
                      demote();
                      written.countDown();
                      await(commit);
                    }));
    assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();

    CompletableFuture<Boolean> check =
        CompletableFuture.supplyAsync(() -> accountSessions.isCurrent("ada", version));
    Thread.sleep(300);
    assertThat(check).isNotDone();

    commit.countDown();
    change.get(10, TimeUnit.SECONDS);
    assertThat(check.get(10, TimeUnit.SECONDS)).isFalse();
  }

  @Test
  @DisplayName("an unchanged account signs in")
  void unchangedAccountSignsIn() {
    assertThat(http.login("ada", "correct-horse", Map.of()).statusCode()).isEqualTo(200);
    assertThat(TestDatabase.sessionsOf(jdbc, "ada")).isEqualTo(1);
  }

  // As AccountService.setRole does it, in a transaction and on a thread of its own.
  private void demoteInAnotherRequest() throws Exception {
    CompletableFuture.runAsync(() -> inTransaction(this::demote)).get(10, TimeUnit.SECONDS);
  }

  private void demote() {
    User ada = users.findByUsername("ada").orElseThrow();
    ada.setRole(User.ROLE_USER);
    accountSessions.end(ada);
  }

  private void inTransaction(Runnable work) {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
