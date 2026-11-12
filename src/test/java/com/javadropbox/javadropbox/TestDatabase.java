package com.javadropbox.javadropbox;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Empties the database between tests. Test classes with the same configuration share one Spring
 * context and so one database; each clears everything, children before parents, rather than only
 * the table it happened to write.
 */
final class TestDatabase {

  private TestDatabase() {}

  /**
   * Fails if any account's running total of what it stores differs from what its rows add up to: a
   * change that forgot to count its bytes. Run before {@link #wipe}.
   */
  static void assertUsageMatchesRows(JdbcTemplate jdbc) {
    List<String> drifted =
        jdbc.queryForList(
            "SELECT u.username || ': ' || u.used_bytes || ' counted, ' || s.actual || ' stored'"
                + " FROM users u CROSS JOIN LATERAL (SELECT"
                + " coalesce((SELECT sum(m.size) FROM file_metadata m"
                + "   WHERE m.user_id = u.id AND m.is_directory = false), 0)"
                + " + coalesce((SELECT sum(v.size) FROM file_versions v"
                + "   JOIN file_metadata m ON m.id = v.file_id WHERE m.user_id = u.id), 0) AS actual) s"
                + " WHERE u.used_bytes <> s.actual",
            String.class);
    org.assertj.core.api.Assertions.assertThat(drifted)
        .as("storage counted against the rows")
        .isEmpty();
  }

  static void wipe(JdbcTemplate jdbc) {
    jdbc.execute("DELETE FROM account_links");
    jdbc.execute("DELETE FROM share_links");
    jdbc.execute("DELETE FROM file_history");
    jdbc.execute("DELETE FROM file_versions");
    jdbc.execute("DELETE FROM file_metadata");
    jdbc.execute("DELETE FROM users");
    jdbc.execute("DELETE FROM spring_session");
    jdbc.execute("DELETE FROM one_time_steps");
    jdbc.execute("DELETE FROM login_attempts");
    jdbc.execute("DELETE FROM setup_code");
    jdbc.execute("DELETE FROM demo_reset");
  }

  /** How many sessions the account signed in as {@code username} has. */
  static int sessionsOf(JdbcTemplate jdbc, String username) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM spring_session WHERE principal_name = ?", Integer.class, username);
  }
}
