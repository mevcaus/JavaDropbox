package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.config.LoginAttemptLimiter;
import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.exception.ConflictException;
import com.javadropbox.javadropbox.exception.ForbiddenException;
import com.javadropbox.javadropbox.exception.TooManyRequestsException;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Creates the first account. Until one exists, anyone who can reach the server could claim it, so
 * setup also needs a one-time code that is only printed to the server's log: whoever installed the
 * server can read it, someone who merely found the address cannot.
 */
@Service
public class SetupService {

  public static final int MIN_PASSWORD_LENGTH = 8;

  // BCrypt only looks at the first 72 bytes, and Spring Security refuses anything longer.
  private static final int MAX_PASSWORD_BYTES = 72;
  private static final int MAX_USERNAME_LENGTH = 255;

  private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final int CODE_LENGTH = 10;

  private static final Logger log = LoggerFactory.getLogger(SetupService.class);

  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final AuthService authService;
  private final String configuredCode;
  private final SecureRandom random = new SecureRandom();

  // Wrong codes are throttled per client, like sign-ins. The code itself never changes before a
  // restart: replacing it after wrong guesses would let anyone make the logged code stale.
  private final LoginAttemptLimiter codeAttempts = new LoginAttemptLimiter(Clock.systemUTC());
  private final String code;

  public SetupService(
      UserRepository users,
      PasswordEncoder passwordEncoder,
      AuthService authService,
      @Value("${app.setup.code:}") String configuredCode) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.authService = authService;
    this.configuredCode = configuredCode.trim();
    this.code = this.configuredCode.isEmpty() ? generateCode() : this.configuredCode;
  }

  @EventListener(ApplicationReadyEvent.class)
  public synchronized void announceCodeIfNeeded() {
    if (authService.isSetupRequired()) {
      announce();
    }
  }

  /**
   * Creates the first account. Synchronized, and the check and insert both commit before it
   * returns, so two simultaneous submissions cannot both create an account.
   *
   * @param client the caller's address, which wrong setup codes are throttled by
   */
  public synchronized void createFirstUser(
      String client, String setupCode, String username, String password) {
    if (!authService.isSetupRequired()) {
      throw new ConflictException("Setup has already been completed");
    }
    checkCode(client, setupCode);

    String name = username == null ? "" : username.trim();
    if (name.isEmpty()) {
      throw new BadRequestException("Username required");
    }
    if (name.length() > MAX_USERNAME_LENGTH) {
      throw new BadRequestException("Username is too long");
    }
    if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
      throw new BadRequestException(
          "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
    }
    if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
      throw new BadRequestException("Password is too long");
    }

    users.save(new User(name, passwordEncoder.encode(password), "ROLE_ADMIN"));
    log.info("Setup complete: created the account \"{}\"", name);
  }

  private void checkCode(String client, String submitted) {
    Duration wait = codeAttempts.tryAcquire(client);
    if (!wait.isZero()) {
      throw new TooManyRequestsException(
          "Too many wrong setup codes. Try again in " + LoginAttemptLimiter.describe(wait) + ".",
          wait);
    }

    boolean matches =
        submitted != null
            && MessageDigest.isEqual(
                normalize(submitted).getBytes(StandardCharsets.UTF_8),
                normalize(code).getBytes(StandardCharsets.UTF_8));
    if (matches) {
      codeAttempts.recordSuccess(client);
      return;
    }
    codeAttempts.recordFailure(client);
    throw new ForbiddenException("Incorrect setup code. It is printed in the server log.");
  }

  // Codes are shown as XXXXX-XXXXX; accept them typed with or without the dash, in any case.
  private static String normalize(String value) {
    return value.replace("-", "").replace(" ", "").toUpperCase();
  }

  private String generateCode() {
    StringBuilder builder = new StringBuilder(CODE_LENGTH + 1);
    for (int i = 0; i < CODE_LENGTH; i++) {
      if (i == CODE_LENGTH / 2) {
        builder.append('-');
      }
      builder.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
    }
    return builder.toString();
  }

  private void announce() {
    String line = "=".repeat(60);
    log.warn(
        "\n{}\n No account exists yet. Open the app and create one with this setup code:\n\n"
            + "     {}\n\n The code changes on every restart until setup is done.\n{}",
        line,
        code,
        line);
  }
}
