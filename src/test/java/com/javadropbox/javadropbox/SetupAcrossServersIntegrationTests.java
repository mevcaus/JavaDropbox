package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.config.LoginAttemptLimiter;
import com.javadropbox.javadropbox.config.SetupFilter;
import com.javadropbox.javadropbox.exception.ConflictException;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.AuthService;
import com.javadropbox.javadropbox.service.FirstAccountImport;
import com.javadropbox.javadropbox.service.SetupService;
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

  // The code is package-private to the service, as the tests there read it.
  private static String code(SetupService server) {
    return (String) org.springframework.test.util.ReflectionTestUtils.invokeMethod(server, "code");
  }
}
