package com.javadropbox.javadropbox;

import com.javadropbox.javadropbox.config.SetupFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Puts the app back the way a new install starts, for tests of what happens before setup. Emptying
 * the database is not enough on its own: {@link SetupFilter} remembers once it has seen an account,
 * which is right in production, where accounts are never all deleted, but these tests delete them.
 */
final class FreshInstall {

  private FreshInstall() {}

  static void restore(JdbcTemplate jdbc, SetupFilter setupFilter) {
    TestDatabase.wipe(jdbc);
    ReflectionTestUtils.setField(setupFilter, "setupComplete", false);
  }
}
