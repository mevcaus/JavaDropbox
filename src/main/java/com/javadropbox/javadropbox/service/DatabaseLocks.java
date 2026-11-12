package com.javadropbox.javadropbox.service;

import org.springframework.jdbc.core.JdbcTemplate;

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
   */
  static void holdUntilCommit(JdbcTemplate jdbc, long key) {
    jdbc.execute("SELECT pg_advisory_xact_lock(" + key + ")");
  }
}
