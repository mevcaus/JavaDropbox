package com.javadropbox.javadropbox.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * PostgreSQL advisory locks, for work only one server may do at a time when several share the
 * database: {@code synchronized} only keeps out the threads of one. Each key names one such job.
 */
final class DatabaseLocks {

  /** Creating the first account. */
  static final long SETUP = 0x4a44_4253_6574_7570L;

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
}
