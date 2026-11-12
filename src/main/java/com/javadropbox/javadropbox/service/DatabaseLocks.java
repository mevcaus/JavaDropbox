package com.javadropbox.javadropbox.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * PostgreSQL advisory locks, for work only one server may do at a time when several share the
 * database: {@code synchronized} only keeps out the threads of one. Each key names one such job.
 */
final class DatabaseLocks {

  /** Creating the first account. */
  static final long SETUP = 0x4a44_4253_6574_7570L;

  /** Creating the public demo's account. */
  static final long DEMO_ACCOUNT = 0x4a44_4244_656d_6f41L;

  /** Resetting the public demo's files. */
  static final long DEMO_RESET = 0x4a44_4244_656d_6f52L;

  private static final Duration RETRY_EVERY = Duration.ofMillis(200);

  private static final Logger log = LoggerFactory.getLogger(DatabaseLocks.class);

  private DatabaseLocks() {}

  /**
   * Takes the lock {@code key}, waiting while another server or thread holds it, and holds it until
   * the current transaction ends. Called outside a transaction, it would be let go at once.
   *
   * <ul>
   *   <li>It waits as long as it takes, holding the transaction's connection all the while: take it
   *       for short work, and nothing in that work may need a second connection, or requests
   *       waiting for the lock can use up the pool while the one holding it waits for a connection.
   *   <li>What the transaction reads after the lock sees what the holder before it committed, under
   *       PostgreSQL's default READ COMMITTED. Under REPEATABLE READ or SERIALIZABLE it wouldn't,
   *       since the snapshot is taken when this statement starts, before it waits.
   *   <li>For a job that should be skipped while another server runs it, rather than waited for,
   *       {@code pg_try_advisory_xact_lock} is the one to add.
   * </ul>
   */
  static void holdUntilCommit(JdbcTemplate jdbc, long key) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("An advisory lock held until commit needs a transaction");
    }
    jdbc.query("SELECT pg_advisory_xact_lock(?)", row -> null, key);
  }

  /** The work {@link #whileHeld} runs. */
  @FunctionalInterface
  interface Work {
    void run() throws IOException;
  }

  /**
   * Runs {@code work} holding the lock {@code key}, for work too long for one transaction, which
   * makes transactions of its own. Waits up to {@code wait} while another server or thread holds
   * it, and returns false without running the work if it is still held then: one server's stalled
   * work mustn't hold up another's requests for good. Returns true once the work has run.
   *
   * <ul>
   *   <li>The lock is held on a connection of its own, outside any transaction, for as long as the
   *       work takes, and let go when it ends, or by PostgreSQL should the connection drop. That
   *       connection is one more from the pool, besides those the work takes, so call it from one
   *       thread per server at a time.
   *   <li>It isn't re-entrant: work that asks for the same lock again waits for itself.
   *   <li>It needs a direct connection to PostgreSQL. Behind a pooler such as PgBouncer in
   *       transaction mode, taking and letting go of the lock could reach different sessions.
   * </ul>
   */
  static boolean whileHeld(JdbcTemplate jdbc, long key, Duration wait, Work work)
      throws IOException {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      // Inside one, the connection would be the transaction's, and the work's would join it.
      throw new IllegalStateException("A lock held while work runs needs no transaction");
    }
    try {
      return jdbc.execute(
          (ConnectionCallback<Boolean>)
              connection -> {
                if (!take(connection, key, wait)) {
                  return false;
                }
                try {
                  work.run();
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                } finally {
                  letGo(connection, key);
                }
                return true;
              });
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  // Tries again every so often until the wait is over. PostgreSQL's own lock_timeout would limit
  // pg_advisory_lock too, but set for the session it would stay on the pooled connection after.
  private static boolean take(Connection connection, long key, Duration wait) throws SQLException {
    long deadline = System.nanoTime() + wait.toNanos();
    while (true) {
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
        statement.setLong(1, key);
        try (ResultSet result = statement.executeQuery()) {
          result.next();
          if (result.getBoolean(1)) {
            return true;
          }
        }
      }
      if (System.nanoTime() - deadline >= 0) {
        return false;
      }
      try {
        Thread.sleep(RETRY_EVERY.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for the database lock " + key);
      }
    }
  }

  // A failure to let go is logged, not thrown, so it neither hides the work's own failure nor
  // turns work that succeeded into one. The connection is most likely gone, and the lock with it.
  private static void letGo(Connection connection, long key) {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
      statement.setLong(1, key);
      try (ResultSet result = statement.executeQuery()) {
        // False when the session no longer held it: its connection dropped while the work ran, and
        // another server may have taken it meanwhile.
        if (result.next() && !result.getBoolean(1)) {
          log.warn("The database lock {} was lost while its work ran", key);
        }
      }
    } catch (SQLException e) {
      log.warn("Could not let go of the database lock {}: {}", key, e.toString());
    }
  }
}
