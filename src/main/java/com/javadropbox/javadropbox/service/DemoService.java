package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
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
import org.springframework.util.unit.DataSize;

/**
 * The public demo ({@code demo} profile): a shared account whose credentials the sign-in page
 * shows, so nobody has to go through setup, and a daily reset to a few sample files.
 *
 * <p>The reset is due once a day at {@code javadropbox.demo.reset-at} (UTC). It is not a scheduled
 * task: the demo's host suspends the server while nobody uses it, and a timer cannot fire then.
 * Instead the server checks at startup and on every request (see DemoResetFilter), so the first
 * visitor after the reset time gets fresh files before anything else happens.
 *
 * <p>Several servers sharing the database reset the files once a day between them: the time of the
 * last reset is kept in the database, and a reset holds a lock there, so a server that waited for
 * another's sees it done and doesn't do it again. Creating the account holds one too.
 */
@Service
@Profile("demo")
public class DemoService implements ApplicationRunner {

  // Where releases before the time was kept in the database kept it: as this file's modification
  // time, in the app's own folder. Read once if the database has none, then deleted.
  private static final String OLD_MARKER = "demo-reset";

  // After a failed reset, how long until the next request tries again.
  private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(10);

  // How long a server waits while another resets the files, its requests waiting with it. A reset
  // takes a few seconds.
  private static final Duration RESET_LOCK_WAIT = Duration.ofSeconds(30);

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
  private final FileStore store;
  private final SearchIndex searchIndex;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final AccountSessions sessions;
  private final Clock clock;
  private final String username;
  private final String password;
  private final LocalTime resetAt;
  // What the shared account may store, previous versions included; null for no limit.
  private final Long quotaBytes;

  // Never until run() has worked it out: the server takes requests before the account exists.
  private volatile Instant nextReset = Instant.MAX;

  @Autowired
  public DemoService(
      UserRepository users,
      PasswordEncoder passwordEncoder,
      FileService fileService,
      StoragePaths storagePaths,
      FileStore store,
      SearchIndex searchIndex,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      AccountSessions sessions,
      @Value("${javadropbox.demo.username}") String username,
      @Value("${javadropbox.demo.password}") String password,
      @Value("${javadropbox.demo.reset-at}") LocalTime resetAt,
      @Value("${javadropbox.demo.quota:}") String quota) {
    this(
        users,
        passwordEncoder,
        fileService,
        storagePaths,
        store,
        searchIndex,
        jdbc,
        transactionManager,
        sessions,
        Clock.systemUTC(),
        username,
        password,
        resetAt,
        quota.isBlank() ? null : DataSize.parse(quota.trim()).toBytes());
  }

  DemoService(
      UserRepository users,
      PasswordEncoder passwordEncoder,
      FileService fileService,
      StoragePaths storagePaths,
      FileStore store,
      SearchIndex searchIndex,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      AccountSessions sessions,
      Clock clock,
      String username,
      String password,
      LocalTime resetAt,
      Long quotaBytes) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.fileService = fileService;
    this.storagePaths = storagePaths;
    this.store = store;
    this.searchIndex = searchIndex;
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
    this.sessions = sessions;
    this.clock = clock;
    this.username = username;
    this.password = password;
    this.resetAt = resetAt;
    this.quotaBytes = quotaBytes;
  }

  public String username() {
    return username;
  }

  public String password() {
    return password;
  }

  /** What the shared account may store, previous versions included; null for no limit. */
  public Long quotaBytes() {
    return quotaBytes;
  }

  /** When the files will next be reset. */
  public Instant nextReset() {
    return nextReset;
  }

  // Runs before the application is ready, so the account exists before SetupService looks for one
  // and no setup code is ever printed.
  @Override
  public void run(ApplicationArguments args) throws IOException {
    // In one transaction, so the account's sessions end along with the change that ends them.
    transactions.executeWithoutResult(status -> ensureAccount());
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
        boolean ran =
            DatabaseLocks.whileHeld(
                jdbc,
                DatabaseLocks.DEMO_RESET,
                RESET_LOCK_WAIT,
                () -> {
                  // Another server may have reset the files while this one waited.
                  Instant last = lastReset();
                  Instant due = resetTimeAfter(last);
                  if (clock.instant().isBefore(due)) {
                    log.info("The demo's files were already reset, at {}", last);
                    nextReset = due;
                    return;
                  }
                  reset();
                  nextReset = resetTimeAfter(clock.instant());
                });
        if (!ran) {
          log.warn(
              "Another server has been resetting the demo's files for over {}; trying again in {}",
              RESET_LOCK_WAIT,
              RETRY_AFTER_FAILURE);
          nextReset = clock.instant().plus(RETRY_AFTER_FAILURE);
        }
      } catch (IOException | RuntimeException e) {
        log.error("Could not reset the demo's files", e);
        nextReset = clock.instant().plus(RETRY_AFTER_FAILURE);
      }
    }
  }

  // The account is created on the first start, and its password and quota put back if they were
  // changed, e.g. through the recovery procedure in docs/self-hosting.md. Everyone shares it, so it
  // must never manage accounts: upgrading to accounts of their own made the first account, which on
  // the demo is this one, an admin.
  private void ensureAccount() {
    // Two servers starting at once would otherwise both create it, and one would fail to start.
    DatabaseLocks.holdUntilCommit(jdbc, DatabaseLocks.DEMO_ACCOUNT);
    User user = users.findByUsername(username).orElse(null);
    if (user == null) {
      User created = new User(username, passwordEncoder.encode(password), User.ROLE_USER);
      created.setQuotaBytes(quotaBytes);
      users.save(created);
      log.info("Created the demo account \"{}\"", username);
      return;
    }
    boolean changed = false;
    // Unlike a reset link, this leaves the sessions alone: everyone shares the account, and its
    // password is on the sign-in page, so a new one keeps nobody out who was in.
    if (!passwordEncoder.matches(password, user.getPassword())) {
      user.setPassword(passwordEncoder.encode(password));
      changed = true;
      log.info("Reset the demo account's password");
    }
    if (!Objects.equals(user.getQuotaBytes(), quotaBytes)) {
      user.setQuotaBytes(quotaBytes);
      changed = true;
      log.info("Reset the demo account's quota");
    }
    if (user.isAdmin() || !user.isEnabled()) {
      user.setRole(User.ROLE_USER);
      user.setEnabled(true);
      sessions.end(user);
      changed = true;
      log.info("Made the demo account an ordinary account that can sign in");
    }
    if (changed) {
      users.save(user);
    }
  }

  /** Deletes every file, folder, version, share link and history entry, then stores the samples. */
  void reset() throws IOException {
    // The rows first: if that fails nothing is lost, and a later retry starts from the same state.
    transactions.executeWithoutResult(
        status -> {
          // The accounts first, so an upload committing meanwhile is counted after the reset
          // rather than set back to zero by it.
          jdbc.queryForList("SELECT id FROM users FOR UPDATE");
          jdbc.update("DELETE FROM share_links");
          jdbc.update("DELETE FROM file_versions");
          jdbc.update("DELETE FROM file_history");
          jdbc.update("DELETE FROM file_metadata");
          jdbc.update("DELETE FROM blobs");
          // Nothing is stored any more; storing the samples counts them again.
          jdbc.update("UPDATE users SET used_bytes = 0");
        });

    // The app's own folder stays: it holds the search index.
    for (FileStore.Entry entry : store.list("")) {
      if (!entry.key().equals(StoragePaths.INTERNAL_DIR)) {
        store.deleteRecursively(entry.key());
      }
    }
    // Deleted behind FileService's back, so the index has to be told.
    searchIndex.changedEverywhere();

    User owner = users.findByUsername(username).orElseThrow();
    for (Sample sample : SAMPLE_FILES) {
      fileService.store(
          sample.folder(),
          sample.name(),
          new ClassPathResource(SAMPLES + sample.resource()),
          owner);
    }

    jdbc.update(
        "INSERT INTO demo_reset (id, reset_at) VALUES (1, ?)"
            + " ON CONFLICT (id) DO UPDATE SET reset_at = EXCLUDED.reset_at",
        clock.instant().atOffset(ZoneOffset.UTC));
    Files.deleteIfExists(storagePaths.internalDir().resolve(OLD_MARKER));
    log.info("Reset the demo's files");
  }

  private Instant lastReset() throws IOException {
    List<Instant> stored =
        jdbc.query(
            "SELECT reset_at FROM demo_reset",
            (row, number) -> row.getObject(1, OffsetDateTime.class).toInstant());
    if (!stored.isEmpty()) {
      return stored.getFirst();
    }
    try {
      return Files.getLastModifiedTime(storagePaths.internalDir().resolve(OLD_MARKER)).toInstant();
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
