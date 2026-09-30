package db.migration;

import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Stores timestamps as absolute instants ({@code timestamptz}) instead of zone-less local times.
 *
 * <p>The entities used to write {@code LocalDateTime.now()}: the server's wall-clock time with no
 * zone, so moving the server (or its container) to another time zone silently shifted every stored
 * time. Those values were written in this JVM's default zone, which SQL cannot know, so this is a
 * Java migration that converts them using that zone. It assumes the zone has not changed since they
 * were written -- the same assumption the application itself made when reading them.
 *
 * <p>Flyway keeps no checksum for Java migrations ({@link BaseJavaMigration#getChecksum} is null),
 * so this class can be corrected without failing validation on databases that already ran it.
 */
public class V3__timestamps_with_time_zone extends BaseJavaMigration {

  private static final String[][] COLUMNS = {
    {"file_metadata", "created_at"},
    {"file_metadata", "updated_at"},
    {"file_metadata", "last_accessed"},
    {"file_versions", "created_at"},
    {"file_history", "\"timestamp\""},
  };

  @Override
  public void migrate(Context context) throws Exception {
    String zone = sqlZone(ZoneId.systemDefault());
    try (Statement statement = context.getConnection().createStatement()) {
      for (String[] column : COLUMNS) {
        statement.execute(
            "ALTER TABLE "
                + column[0]
                + " ALTER COLUMN "
                + column[1]
                + " TYPE timestamp(6) with time zone USING "
                + column[1]
                + " AT TIME ZONE "
                + zone);
      }
    }
  }

  /**
   * The zone as PostgreSQL should read it. A fixed offset goes in as an interval: as text, a zone
   * like {@code GMT+01:00} (what {@code -Duser.timezone=GMT+1} gives) or {@code +01:00} is read
   * POSIX-style, with the sign inverted, and times would shift by twice the offset.
   */
  private static String sqlZone(ZoneId zone) {
    if (zone.getRules().isFixedOffset()) {
      int seconds = zone.getRules().getOffset(Instant.EPOCH).getTotalSeconds();
      return String.format(
          "INTERVAL '%s%02d:%02d:%02d'",
          seconds < 0 ? "-" : "+",
          Math.abs(seconds) / 3600,
          Math.abs(seconds) / 60 % 60,
          Math.abs(seconds) % 60);
    }
    String id = zone.getId();
    // Region ids are letters, digits and / _ + - ; anything else must not reach the SQL.
    if (!id.matches("[A-Za-z0-9/_+\\-]+")) {
      throw new IllegalStateException("Unexpected time zone id: " + id);
    }
    return "'" + id + "'";
  }
}
