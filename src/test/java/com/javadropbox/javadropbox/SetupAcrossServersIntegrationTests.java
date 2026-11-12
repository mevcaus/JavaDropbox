package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.config.LoginAttemptLimiter;
import com.javadropbox.javadropbox.config.SetupFilter;
import com.javadropbox.javadropbox.exception.ConflictException;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.AuthService;
import com.javadropbox.javadropbox.service.FirstAccountImport;
import com.javadropbox.javadropbox.service.SetupService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Setup with two servers on one database: this context's SetupService, and a second one made from
 * the same beans, as another server would have its own.
 */
@SpringBootTest
@TempServingDirectory
@DisplayName("Setup across servers")
@Timeout(120)
class SetupAcrossServersIntegrationTests {

  private static final String CLIENT = "192.0.2.1";

  @Autowired private SetupService setup;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private AuthService authService;
  @Autowired private FirstAccountImport firstAccountImport;

  @Autowired
  @Qualifier(LoginAttemptLimiter.SETUP_CODE)
  private LoginAttemptLimiter codeAttempts;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private SetupFilter setupFilter;

  private SetupService otherServer;

  @BeforeEach
  void setUp() {
    FreshInstall.restore(jdbc, setupFilter);
    otherServer =
        new SetupService(
            users,
            passwordEncoder,
            authService,
            firstAccountImport,
            codeAttempts,
            jdbc,
            transactions,
            "");
  }

  @Test
  @DisplayName("the code one server printed sets the install up on the other")
  void codeFromOneServerWorksOnTheOther() {
    otherServer.announceCodeIfNeeded();
    String printed = code(otherServer);

    setup.createFirstUser(CLIENT, printed, "ada", "long enough");

    assertThat(users.findByUsername("ada")).isPresent();
  }

  // Each round, both servers are sent the right code at the same moment, for different accounts.
  @Test
  @DisplayName("two servers set up at the same moment create one account, and refuse the other")
  void onlyOneFirstAccount() throws Exception {
    for (int round = 0; round < 10; round++) {
      FreshInstall.restore(jdbc, setupFilter);
      String code = code(setup);
      CountDownLatch go = new CountDownLatch(1);
      List<Callable<String>> submissions = new ArrayList<>();
      int r = round;
      for (SetupService server : List.of(setup, otherServer)) {
        String name = (server == setup ? "first" : "second") + r;
        submissions.add(
            () -> {
              go.await(10, TimeUnit.SECONDS);
              try {
                server.createFirstUser(CLIENT, code, name, "long enough");
                return "created";
              } catch (ConflictException e) {
                return "refused";
              }
            });
      }
      List<String> outcomes = new ArrayList<>();
      try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> submission : submissions) {
          futures.add(threads.submit(submission));
        }
        go.countDown();
        for (Future<String> future : futures) {
          outcomes.add(future.get());
        }
      }

      assertThat(outcomes).as("round " + round).containsExactlyInAnyOrder("created", "refused");
      assertThat(users.count()).as("round " + round).isEqualTo(1);
    }
  }

  // The code the server printed, as every server prints the same one.
  private String code(SetupService server) {
    server.announceCodeIfNeeded();
    return jdbc.queryForObject("SELECT code FROM setup_code", String.class);
  }

  // More submissions at once than the connection pool has connections (10), each with the right
  // code, as a crowd could send. Waiting for the lock must not take every connection while the
  // one holding it needs another.
  @Test
  @DisplayName("a crowd of setups at once, on both servers, creates one account without stalling")
  void crowdDoesNotStall() throws Exception {
    String code = code(setup);
    CountDownLatch go = new CountDownLatch(1);
    List<Callable<String>> submissions = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      SetupService server = i % 2 == 0 ? setup : otherServer;
      String client = "192.0.2." + (i + 10);
      String name = "crowd" + i;
      submissions.add(
          () -> {
            go.await(10, TimeUnit.SECONDS);
            try {
              server.createFirstUser(client, code, name, "long enough");
              return "created";
            } catch (ConflictException e) {
              return "refused";
            }
          });
    }
    long started = System.nanoTime();
    List<String> outcomes = new ArrayList<>();
    try (ExecutorService threads = Executors.newFixedThreadPool(15)) {
      List<Future<String>> futures = new ArrayList<>();
      for (Callable<String> submission : submissions) {
        futures.add(threads.submit(submission));
      }
      go.countDown();
      for (Future<String> future : futures) {
        outcomes.add(future.get());
      }
    }

    assertThat(outcomes).containsOnlyOnce("created").hasSize(15);
    assertThat(users.count()).isEqualTo(1);
    // Hikari gives up waiting for a connection after 30 seconds.
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
  }

  @Test
  @DisplayName("a server starting once setup is done neither prints a code nor stores one")
  void noCodeAfterSetup() {
    setup.createFirstUser(CLIENT, code(setup), "ada", "long enough");

    otherServer.announceCodeIfNeeded();

    assertThat(jdbc.queryForObject("SELECT count(*) FROM setup_code", Integer.class)).isZero();
  }
}
