package com.javadropbox.javadropbox;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Empties the database between tests. Test classes with the same configuration share one Spring
 * context and so one database; each clears everything, children before parents, rather than only
 * the table it happened to write.
 */
final class TestDatabase {

  private TestDatabase() {}

  static void wipe(JdbcTemplate jdbc) {
    jdbc.execute("DELETE FROM account_links");
    jdbc.execute("DELETE FROM share_links");
    jdbc.execute("DELETE FROM file_history");
    jdbc.execute("DELETE FROM file_versions");
    jdbc.execute("DELETE FROM file_metadata");
    jdbc.execute("DELETE FROM users");
    jdbc.execute("DELETE FROM spring_session");
    jdbc.execute("DELETE FROM one_time_steps");
  }

  /** How many sessions the account signed in as {@code username} has. */
  static int sessionsOf(JdbcTemplate jdbc, String username) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM spring_session WHERE principal_name = ?", Integer.class, username);
  }
}
