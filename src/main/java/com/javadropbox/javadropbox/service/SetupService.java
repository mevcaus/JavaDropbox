package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.config.LoginAttemptLimiter;
import com.javadropbox.javadropbox.exception.ConflictException;
import com.javadropbox.javadropbox.exception.ForbiddenException;
import com.javadropbox.javadropbox.exception.TooManyRequestsException;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the first account, an admin, who invites everyone else. Until it exists, anyone who can
 * reach the server could claim it, so setup also needs a one-time code that is only printed to the
 * server's log: whoever installed the server can read it, someone who merely found the address
 * cannot.
 *
 * <p>Several servers sharing the database share the code too: whichever needs one first generates
 * it and keeps it in the database until setup is done, and every server prints and checks that one.
 * Creating the first account holds a lock in the database, so two servers can't each create one.
 */
@Service
public class SetupService {

  private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final int CODE_LENGTH = 10;
  // A configured code must be at least as long as a generated one.
  private static final int MIN_CONFIGURED_CODE_LENGTH = CODE_LENGTH;

  private static final Logger log = LoggerFactory.getLogger(SetupService.class);

  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final AuthService authService;
  private final FirstAccountImport firstAccountImport;
  private final String configuredCode;
  private final SecureRandom random = new SecureRandom();
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  // Wrong codes are throttled per client, like sign-ins, and counted apart from them. The code
  // itself never changes before setup is done: replacing it after wrong guesses would let anyone
  // make the logged code stale.
  private final LoginAttemptLimiter codeAttempts;

  public SetupService(
      UserRepository users,
      PasswordEncoder passwordEncoder,
      AuthService authService,
      FirstAccountImport firstAccountImport,
      @Qualifier(LoginAttemptLimiter.SETUP_CODE) LoginAttemptLimiter codeAttempts,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      @Value("${app.setup.code:}") String configuredCode) {
    this.users = users;
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactions);
    this.codeAttempts = codeAttempts;
    this.passwordEncoder = passwordEncoder;
    this.authService = authService;
    this.firstAccountImport = firstAccountImport;
    this.configuredCode = configuredCode.trim();
    if (!this.configuredCode.isEmpty()
        && normalize(this.configuredCode).length() < MIN_CONFIGURED_CODE_LENGTH) {
      throw new IllegalStateException(
          "app.setup.code must be at least "
              + MIN_CONFIGURED_CODE_LENGTH
              + " characters long, not counting dashes and spaces. Remove it to have a random"
              + " code generated instead.");
    }
  }

  /**
   * The code to print: the configured one, or the one kept in the database, which the first server
   * to start generates. Only a server printing it makes one, so that no code is ever made that
   * nobody has seen.
   */
  String code() {
    Optional<String> current = currentCode();
    if (current.isPresent()) {
      return current.get();
    }
    // Two servers starting at once both get the one that was stored first.
    return jdbc.queryForObject(
        "INSERT INTO setup_code (id, code, created_at) VALUES (1, ?, now())"
            + " ON CONFLICT (id) DO UPDATE SET id = EXCLUDED.id RETURNING code",
        String.class,
        generateCode());
  }

  // Under the setup lock, so that a server starting while another finishes setup waits for it,
  // and then neither prints a code nor stores one that nothing would ever use up.
  @EventListener(ApplicationReadyEvent.class)
  public void announceCodeIfNeeded() {
    transaction.executeWithoutResult(
        status -> {
          DatabaseLocks.holdUntilCommit(jdbc, DatabaseLocks.SETUP);
          if (authService.isSetupRequired()) {
            announce();
          }
        });
  }

  /**
   * Creates the first account, which is given whatever is already at the top of the store. The
   * check and the insert happen in one transaction, holding a lock in the database, and commit
   * before it returns, so two submissions at once, to one server or to two, can't both create an
   * account.
   *
   * @param client the caller's address, which wrong setup codes are throttled by
   */
  public void createFirstUser(String client, String setupCode, String username, String password) {
    // Everything that can be done before taking the lock is: checking the code counts the attempt
    // on a connection of its own, and requests waiting for the lock each hold one, so taking a
    // second while holding the lock could wait for one that never comes free.
    if (!authService.isSetupRequired()) {
      throw new ConflictException("Setup has already been completed");
    }
    checkCode(client, setupCode);
    String name = Credentials.username(username);
    Credentials.checkPassword(password);
    String hash = passwordEncoder.encode(password);

    User user =
        transaction.execute(
            status -> {
              DatabaseLocks.holdUntilCommit(jdbc, DatabaseLocks.SETUP);
              if (!authService.isSetupRequired()) {
                throw new ConflictException("Setup has already been completed");
              }
              User saved = users.save(new User(name, hash, User.ROLE_ADMIN));
              // Used up: a later install on this database would need a new one.
              jdbc.update("DELETE FROM setup_code");
              return saved;
            });
    log.info("Setup complete: created the account \"{}\"", name);
    firstAccountImport.forNewFirstAccount(user);
  }

  // The configured code, or the one stored; none once setup is done, or before any server has
  // printed one.
  private Optional<String> currentCode() {
    if (!configuredCode.isEmpty()) {
      return Optional.of(configuredCode);
    }
    return jdbc.queryForList("SELECT code FROM setup_code", String.class).stream().findFirst();
  }

  private void checkCode(String client, String submitted) {
    Optional<String> expected = currentCode();
    // Used up by another request, here or on another server, since this one checked.
    if (expected.isEmpty() && !authService.isSetupRequired()) {
      throw new ConflictException("Setup has already been completed");
    }
    Duration wait = codeAttempts.tryAcquire(client);
    if (!wait.isZero()) {
      throw new TooManyRequestsException(
          "Too many wrong setup codes. Try again in " + LoginAttemptLimiter.describe(wait) + ".",
          wait);
    }

    boolean matches =
        submitted != null
            && expected.isPresent()
            && MessageDigest.isEqual(
                normalize(submitted).getBytes(StandardCharsets.UTF_8),
                normalize(expected.get()).getBytes(StandardCharsets.UTF_8));
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
    // Whoever configured the code knows it; printing it would only hand it to log readers.
    if (!configuredCode.isEmpty()) {
      log.warn(
          "\n{}\n No account exists yet. Open the app and create one with the setup code set in"
              + " app.setup.code.\n{}",
          line,
          line);
      return;
    }
    log.warn(
        "\n{}\n No account exists yet. Open the app and create one with this setup code:\n\n"
            + "     {}\n\n The code stays the same until setup is done.\n{}",
        line,
        code(),
        line);
  }
}
