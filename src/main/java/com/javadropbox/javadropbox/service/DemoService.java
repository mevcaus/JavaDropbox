package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The public demo ({@code demo} profile): a shared account whose credentials the sign-in page
 * shows, so nobody has to go through setup, and a daily reset to a few sample files.
 *
 * <p>The reset is due once a day at {@code javadropbox.demo.reset-at} (UTC). It is not a scheduled
 * task: the demo's host suspends the server while nobody uses it, and a timer cannot fire then.
 * Instead the server checks at startup and on every request (see DemoResetFilter), so the first
 * visitor after the reset time gets fresh files before anything else happens.
 */
@Service
@Profile("demo")
public class DemoService implements ApplicationRunner {

  /** When the last reset finished, as the file's modification time. Survives restarts. */
  private static final String MARKER = "demo-reset";

  // After a failed reset, how long until the next request tries again.
  private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(10);

  private static final String SAMPLES = "demo/";

  private record Sample(String folder, String name, String resource) {}

  // Stored in this order. The three notes are one file uploaded three times, so it has versions.
  private static final List<Sample> SAMPLE_FILES =
      List.of(
          new Sample("", "Welcome.md", "Welcome.md"),
          new Sample("Documents", "JavaDropbox overview.pdf", "overview.pdf"),
          new Sample("Pictures", "javadropbox-logo.png", "javadropbox-logo.png"),
          new Sample("Code", "Greeter.java", "Greeter.java.txt"),
          new Sample("Notes", "todo.txt", "todo-1.txt"),
          new Sample("Notes", "todo.txt", "todo-2.txt"),
          new Sample("Notes", "todo.txt", "todo-3.txt"));

  private static final Logger log = LoggerFactory.getLogger(DemoService.class);

  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final FileService fileService;
  private final StoragePaths storagePaths;
  private final SearchIndex searchIndex;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final String username;
  private final String password;
  private final LocalTime resetAt;

  // Never until run() has worked it out: the server takes requests before the account exists.
  private volatile Instant nextReset = Instant.MAX;

  @Autowired
  public DemoService(
      UserRepository users,
      PasswordEncoder passwordEncoder,
      FileService fileService,
      StoragePaths storagePaths,
      SearchIndex searchIndex,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      @Value("${javadropbox.demo.username}") String username,
      @Value("${javadropbox.demo.password}") String password,
      @Value("${javadropbox.demo.reset-at}") LocalTime resetAt) {
    this(
        users,
        passwordEncoder,
        fileService,
        storagePaths,
        searchIndex,
        jdbc,
        transactionManager,
        Clock.systemUTC(),
        username,
        password,
        resetAt);
  }

  DemoService(
      UserRepository users,
      PasswordEncoder passwordEncoder,
      FileService fileService,
      StoragePaths storagePaths,
      SearchIndex searchIndex,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      Clock clock,
      String username,
      String password,
      LocalTime resetAt) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.fileService = fileService;
    this.storagePaths = storagePaths;
    this.searchIndex = searchIndex;
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
    this.username = username;
    this.password = password;
    this.resetAt = resetAt;
  }

  public String username() {
    return username;
  }

  public String password() {
    return password;
  }

  /** When the files will next be reset. */
  public Instant nextReset() {
    return nextReset;
  }

  // Runs before the application is ready, so the account exists before SetupService looks for one
  // and no setup code is ever printed.
  @Override
  public void run(ApplicationArguments args) throws IOException {
    ensureAccount();
    nextReset = resetTimeAfter(lastReset());
    resetIfDue();
  }

  /**
   * Resets the files if the reset time has passed since the last reset. Cheap when it has not,
   * which is every call but one a day.
   */
  public void resetIfDue() {
    if (clock.instant().isBefore(nextReset)) {
      return;
    }
    synchronized (this) {
      if (clock.instant().isBefore(nextReset)) {
        return;
      }
      try {
        reset();
        nextReset = resetTimeAfter(clock.instant());
      } catch (IOException | RuntimeException e) {
        log.error("Could not reset the demo's files", e);
        nextReset = clock.instant().plus(RETRY_AFTER_FAILURE);
      }
    }
  }

  // The account is created on the first start and its password put back if it was changed, e.g.
  // through the recovery procedure in docs/self-hosting.md.
  private void ensureAccount() {
    User user = users.findByUsername(username).orElse(null);
    if (user == null) {
      users.save(new User(username, passwordEncoder.encode(password), "ROLE_USER"));
      log.info("Created the demo account \"{}\"", username);
    } else if (!passwordEncoder.matches(password, user.getPassword())) {
      user.setPassword(passwordEncoder.encode(password));
      users.save(user);
      log.info("Reset the demo account's password");
    }
  }

  /** Deletes every file, folder, version, share link and history entry, then stores the samples. */
  void reset() throws IOException {
    // The rows first: if that fails nothing is lost, and a later retry starts from the same state.
    transactions.executeWithoutResult(
        status -> {
          jdbc.update("DELETE FROM share_links");
          jdbc.update("DELETE FROM file_versions");
          jdbc.update("DELETE FROM file_history");
          jdbc.update("DELETE FROM file_metadata");
        });

    Path root = storagePaths.internalDir().getParent();
    try (Stream<Path> entries = Files.list(root)) {
      for (Path entry : entries.toList()) {
        if (!entry.equals(storagePaths.internalDir())) {
          StorageFiles.deleteRecursively(entry);
        }
      }
    }
    // Deleted behind FileService's back, so the index has to be told.
    searchIndex.changed("");

    User owner = users.findByUsername(username).orElseThrow();
    for (Sample sample : SAMPLE_FILES) {
      fileService.store(
          sample.folder(),
          sample.name(),
          new ClassPathResource(SAMPLES + sample.resource()),
          owner);
    }

    Path marker = storagePaths.internalDir().resolve(MARKER);
    Files.createDirectories(marker.getParent());
    Files.writeString(marker, "");
    Files.setLastModifiedTime(marker, FileTime.from(clock.instant()));
    log.info("Reset the demo's files");
  }

  private Instant lastReset() throws IOException {
    try {
      return Files.getLastModifiedTime(storagePaths.internalDir().resolve(MARKER)).toInstant();
    } catch (NoSuchFileException e) {
      return Instant.MIN;
    }
  }

  /** The first reset time strictly after {@code instant}; any time at all if there was none. */
  Instant resetTimeAfter(Instant instant) {
    if (instant.equals(Instant.MIN)) {
      return Instant.MIN;
    }
    ZonedDateTime candidate = instant.atZone(ZoneOffset.UTC).with(resetAt);
    if (!candidate.toInstant().isAfter(instant)) {
      candidate = candidate.plusDays(1);
    }
    return candidate.toInstant();
  }
}
