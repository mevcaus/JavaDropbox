package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.javadropbox.javadropbox.MigratedDatabase;
import com.javadropbox.javadropbox.config.LoginAttemptLimiter;
import com.javadropbox.javadropbox.exception.ConflictException;
import com.javadropbox.javadropbox.exception.ForbiddenException;
import com.javadropbox.javadropbox.exception.TooManyRequestsException;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;

@DisplayName("Setup service")
class SetupServiceTests {

  private static final String OWNER = "192.0.2.1";
  private static final String STRANGER = "198.51.100.66";

  private final UserRepository users = mock(UserRepository.class);
  private final AuthService authService = mock(AuthService.class);
  private final FirstAccountImport firstAccountImport = mock(FirstAccountImport.class);
  private SetupService setup;

  // Wrong codes are counted in the database, as in the app.
  private static final DataSource DATABASE = MigratedDatabase.create();
  private final JdbcTemplate jdbc = new JdbcTemplate(DATABASE);
  private final DataSourceTransactionManager transactions =
      new DataSourceTransactionManager(DATABASE);
  private final LoginAttemptLimiter codeAttempts =
      new LoginAttemptLimiter(
          jdbc, transactions, Clock.systemUTC(), LoginAttemptLimiter.SETUP_CODE);

  @AfterEach
  void forgetAttempts() {
    jdbc.update("DELETE FROM login_attempts");
    jdbc.update("DELETE FROM setup_code");
  }

  @BeforeEach
  void setUp() {
    when(authService.isSetupRequired()).thenReturn(true);
    when(users.save(any(User.class))).thenAnswer(saved -> saved.getArgument(0));
    setup =
        new SetupService(
            users,
            NoOpPasswordEncoder.getInstance(),
            authService,
            firstAccountImport,
            codeAttempts,
            jdbc,
            transactions,
            "");
  }

  @Test
  @DisplayName("the generated code creates the account")
  void generatedCodeWorks() throws Exception {
    setup.createFirstUser(OWNER, code(), "ada", "long enough");

    verify(users).save(any(User.class));
  }

  @Test
  @DisplayName("the account it creates is given whatever is already in the serving directory")
  void createdAccountAdoptsLooseFiles() throws Exception {
    User saved = new User("ada", "long enough", User.ROLE_ADMIN);
    saved.setId(1L);
    when(users.save(any(User.class))).thenReturn(saved);

    setup.createFirstUser(OWNER, code(), "ada", "long enough");

    // The account as it was saved, with its id, which names its folder; and only once it is.
    InOrder order = inOrder(users, firstAccountImport);
    order.verify(users).save(argThat(user -> user.getUsername().equals("ada") && user.isAdmin()));
    order.verify(firstAccountImport).forNewFirstAccount(saved);
  }

  @Test
  @DisplayName("a refused setup gives nobody the files in the serving directory")
  void refusedSetupAdoptsNothing() {
    assertThatThrownBy(() -> setup.createFirstUser(STRANGER, "NOPE", "ada", "long enough"))
        .isInstanceOf(ForbiddenException.class);
    when(authService.isSetupRequired()).thenReturn(false);
    assertThatThrownBy(() -> setup.createFirstUser(OWNER, code(), "ada", "long enough"))
        .isInstanceOf(ConflictException.class);

    verify(firstAccountImport, never()).forNewFirstAccount(any());
  }

  @Test
  @DisplayName("wrong codes from one client do not invalidate the code for anyone else")
  void wrongCodesDoNotReplaceTheCode() throws Exception {
    String original = code();
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> setup.createFirstUser(STRANGER, "NOPE", "ada", "long enough"))
          .isInstanceOf(ForbiddenException.class);
    }

    assertThat(code()).isEqualTo(original);
    assertThatCode(() -> setup.createFirstUser(OWNER, original, "ada", "long enough"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a client that keeps sending wrong codes is throttled, even with the right one")
  void repeatedWrongCodesAreThrottled() throws Exception {
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> setup.createFirstUser(STRANGER, "NOPE", "ada", "long enough"))
          .isInstanceOf(ForbiddenException.class);
    }

    assertThatThrownBy(() -> setup.createFirstUser(STRANGER, code(), "ada", "long enough"))
        .isInstanceOf(TooManyRequestsException.class)
        .hasMessageContaining("Try again in 15 minutes");
    verify(users, never()).save(any(User.class));
  }

  @Test
  @DisplayName("once an account exists, setup is closed even with the right code")
  void setupClosesAfterFirstAccount() throws Exception {
    when(authService.isSetupRequired()).thenReturn(false);

    assertThatThrownBy(() -> setup.createFirstUser(OWNER, code(), "second", "long enough"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  @DisplayName("a username with a line break or another control character is refused")
  void usernameWithControlCharactersRefused() {
    for (String name : new String[] {"ada\nINFO forged", "ada\rb", "a\u0000da", "ada\u2028b"}) {
      assertThatThrownBy(() -> setup.createFirstUser(OWNER, code(), name, "long enough"))
          .hasMessage("Usernames can't contain line breaks or other control characters");
    }
    verify(users, never()).save(any(User.class));
  }

  @Test
  @DisplayName("a password over BCrypt's 72-byte limit is refused rather than truncated")
  void overlongPasswordRefused() {
    assertThatThrownBy(() -> setup.createFirstUser(OWNER, code(), "ada", "ü".repeat(37)))
        .hasMessage("Password is too long");
  }

  @Nested
  @DisplayName("with a configured code")
  @ExtendWith(OutputCaptureExtension.class)
  class ConfiguredCode {

    private static final String CONFIGURED = "Correct-Horse-7";

    @Test
    @DisplayName("wrong codes are throttled like wrong generated ones")
    void configuredCodeIsThrottled() {
      SetupService configured = configured(CONFIGURED);
      for (int i = 0; i < 5; i++) {
        assertThatThrownBy(() -> configured.createFirstUser(STRANGER, "NOPE", "ada", "long enough"))
            .isInstanceOf(ForbiddenException.class);
      }

      assertThatThrownBy(
              () -> configured.createFirstUser(STRANGER, CONFIGURED, "ada", "long enough"))
          .isInstanceOf(TooManyRequestsException.class);
      assertThatCode(() -> configured.createFirstUser(OWNER, CONFIGURED, "ada", "long enough"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one shorter than ten characters, not counting dashes, stops startup")
    void shortConfiguredCodeIsRefused() {
      assertThatThrownBy(() -> configured("abc"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("app.setup.code")
          .hasMessageContaining("at least 10 characters");
      assertThatThrownBy(() -> configured("ABCDE-FGHJ")).isInstanceOf(IllegalStateException.class);
      assertThatCode(() -> configured("ABCDE-FGHJK")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("is never written to the log")
    void configuredCodeIsNotLogged(CapturedOutput output) {
      configured(CONFIGURED).announceCodeIfNeeded();

      assertThat(output.getAll())
          .contains("app.setup.code")
          .doesNotContainIgnoringCase(CONFIGURED)
          .doesNotContainIgnoringCase("CorrectHorse7");
    }

    private SetupService configured(String code) {
      return new SetupService(
          users,
          NoOpPasswordEncoder.getInstance(),
          authService,
          firstAccountImport,
          codeAttempts,
          jdbc,
          transactions,
          code);
    }
  }

  @Test
  @DisplayName("a generated code is printed to the log, where the owner finds it")
  @ExtendWith(OutputCaptureExtension.class)
  void generatedCodeIsLogged(CapturedOutput output) throws Exception {
    setup.announceCodeIfNeeded();

    assertThat(output.getAll()).contains(code());
  }

  private String code() {
    return setup.code();
  }

  // Each server's SetupService, on the database they share.
  @Test
  @DisplayName("servers sharing the database print and take the same code")
  @ExtendWith(OutputCaptureExtension.class)
  void serversShareTheCode(CapturedOutput output) {
    SetupService other =
        new SetupService(
            users,
            NoOpPasswordEncoder.getInstance(),
            authService,
            firstAccountImport,
            codeAttempts,
            jdbc,
            transactions,
            "");
    other.announceCodeIfNeeded();
    String printed = other.code();

    assertThat(output.getAll()).contains(printed);
    assertThat(code()).isEqualTo(printed);
    assertThatCode(() -> setup.createFirstUser(OWNER, printed, "ada", "long enough"))
        .doesNotThrowAnyException();
  }

  // Another server sets up between this request's first look and its check of the code.
  @Test
  @DisplayName("a setup another server finished meanwhile answers 409, and makes no new code")
  void setupFinishedMeanwhile() {
    String printed = code();
    when(authService.isSetupRequired()).thenReturn(true, false);
    jdbc.update("DELETE FROM setup_code");

    assertThatThrownBy(() -> setup.createFirstUser(OWNER, printed, "ada", "long enough"))
        .isInstanceOf(ConflictException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM setup_code", Integer.class)).isZero();
    assertThat(codeAttempts.retryAfter(OWNER)).isZero();
  }

  @Test
  @DisplayName("before any server has printed a code, none is taken")
  void noCodeBeforeOneIsPrinted() {
    assertThatThrownBy(() -> setup.createFirstUser(OWNER, "ABCDE-FGHJK", "ada", "long enough"))
        .isInstanceOf(ForbiddenException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM setup_code", Integer.class)).isZero();
  }

  @Test
  @DisplayName("the code is used up once the account exists")
  void codeIsUsedUp() {
    setup.createFirstUser(OWNER, code(), "ada", "long enough");

    assertThat(jdbc.queryForObject("SELECT count(*) FROM setup_code", Integer.class)).isZero();
  }
}
