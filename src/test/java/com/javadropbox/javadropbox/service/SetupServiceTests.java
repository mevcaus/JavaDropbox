package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.javadropbox.javadropbox.exception.ConflictException;
import com.javadropbox.javadropbox.exception.ForbiddenException;
import com.javadropbox.javadropbox.exception.TooManyRequestsException;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;

@DisplayName("Setup service")
class SetupServiceTests {

  private static final String OWNER = "192.0.2.1";
  private static final String STRANGER = "198.51.100.66";

  private final UserRepository users = mock(UserRepository.class);
  private final AuthService authService = mock(AuthService.class);
  private final LooseFileAdoption adoption = mock(LooseFileAdoption.class);
  private SetupService setup;

  @BeforeEach
  void setUp() {
    when(authService.isSetupRequired()).thenReturn(true);
    setup = new SetupService(users, NoOpPasswordEncoder.getInstance(), authService, adoption, "");
  }

  @Test
  @DisplayName("the generated code creates the account")
  void generatedCodeWorks() throws Exception {
    setup.createFirstUser(OWNER, code(), "ada", "long enough");

    verify(users).save(any(User.class));
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
          users, NoOpPasswordEncoder.getInstance(), authService, adoption, code);
    }
  }

  @Test
  @DisplayName("a generated code is printed to the log, where the owner finds it")
  @ExtendWith(OutputCaptureExtension.class)
  void generatedCodeIsLogged(CapturedOutput output) throws Exception {
    setup.announceCodeIfNeeded();

    assertThat(output.getAll()).contains(code());
  }

  private String code() throws ReflectiveOperationException {
    Field field = SetupService.class.getDeclaredField("code");
    field.setAccessible(true);
    return (String) field.get(setup);
  }
}
