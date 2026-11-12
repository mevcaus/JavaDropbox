package com.javadropbox.javadropbox.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
   * Runs {@code work} holding the lock {@code key}, waiting while another server or thread holds
   * it: for work too long for one transaction, which makes transactions of its own. The lock is
   * held on a connection of its own, outside any transaction, for as long as the work takes, and
   * let go when it ends, or by PostgreSQL should the connection drop. That connection is one more
   * from the pool, besides those the work takes, so call it from one thread per server at a time.
   */
  static void whileHeld(JdbcTemplate jdbc, long key, Work work) throws IOException {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      // Inside one, the connection would be the transaction's, and the work's would join it.
      throw new IllegalStateException("A lock held while work runs needs no transaction");
    }
    try {
      jdbc.execute(
          (ConnectionCallback<Void>)
              connection -> {
                call(connection, "SELECT pg_advisory_lock(?)", key);
                try {
                  work.run();
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                } finally {
                  call(connection, "SELECT pg_advisory_unlock(?)", key);
                }
                return null;
              });
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  private static void call(Connection connection, String sql, long key) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, key);
      statement.executeQuery().close();
    }
  }
}
