package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.javadropbox.javadropbox.exception.ConflictException;
import com.javadropbox.javadropbox.exception.ForbiddenException;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;

@DisplayName("Setup service")
class SetupServiceTests {

  private final UserRepository users = mock(UserRepository.class);
  private final AuthService authService = mock(AuthService.class);
  private SetupService setup;

  @BeforeEach
  void setUp() {
    when(authService.isSetupRequired()).thenReturn(true);
    setup = new SetupService(users, NoOpPasswordEncoder.getInstance(), authService, "");
  }

  @Test
  @DisplayName("the generated code creates the account")
  void generatedCodeWorks() throws Exception {
    setup.createFirstUser(code(), "ada", "long enough");

    verify(users).save(any(User.class));
  }

  @Test
  @DisplayName("repeated wrong guesses replace the code, so it cannot be brute-forced")
  void codeRotatesAfterTooManyWrongGuesses() throws Exception {
    String original = code();
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> setup.createFirstUser("NOPE", "ada", "long enough"))
          .isInstanceOf(ForbiddenException.class);
    }

    assertThatThrownBy(() -> setup.createFirstUser(original, "ada", "long enough"))
        .isInstanceOf(ForbiddenException.class);
    verify(users, never()).save(any(User.class));
    assertThatCode(() -> setup.createFirstUser(code(), "ada", "long enough"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("once an account exists, setup is closed even with the right code")
  void setupClosesAfterFirstAccount() throws Exception {
    when(authService.isSetupRequired()).thenReturn(false);

    assertThatThrownBy(() -> setup.createFirstUser(code(), "second", "long enough"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  @DisplayName("a password over BCrypt's 72-byte limit is refused rather than truncated")
  void overlongPasswordRefused() {
    assertThatThrownBy(() -> setup.createFirstUser(code(), "ada", "ü".repeat(37)))
        .hasMessage("Password is too long");
  }

  private String code() throws ReflectiveOperationException {
    Field field = SetupService.class.getDeclaredField("code");
    field.setAccessible(true);
    return (String) field.get(setup);
  }
}
