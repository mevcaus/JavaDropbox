package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Flyway - V19 blob release")
class FlywayBlobReleaseMigrationTests {

  private static final Database DATABASE = PostgresTestSupport.newDatabase();

  private static final String HASH =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  @BeforeAll
  static void migrate() throws SQLException {
    DATABASE.migrateTo("18");
    DATABASE.execute(
        "INSERT INTO users (username, password, role) VALUES ('ada', 'h', 'ROLE_ADMIN')");
    DATABASE.migrateTo("latest");
  }

  @AfterEach
  void noBlobs() throws SQLException {
    DATABASE.execute("DELETE FROM blobs");
  }

  @Test
  @DisplayName("a blob nothing points at has to say when it was released; a referenced one needn't")
  void unreferencedBlobIsReleased() throws SQLException {
    assertThatThrownBy(
            () ->
                DATABASE.execute(
                    "INSERT INTO blobs (hash, size, reference_count, created_at)"
                        + " VALUES ('"
                        + HASH
                        + "', 3, 0, now())"))
        .isInstanceOf(SQLException.class);

    DATABASE.execute(
        "INSERT INTO blobs (hash, size, reference_count, created_at)"
            + " VALUES ('"
            + HASH
            + "', 3, 1, now())");
    assertThatThrownBy(() -> DATABASE.execute("UPDATE blobs SET reference_count = 0"))
        .isInstanceOf(SQLException.class);
    DATABASE.execute("UPDATE blobs SET reference_count = 0, released_at = now()");
    assertThat(DATABASE.column("SELECT released_at IS NOT NULL FROM blobs")).containsExactly("t");
  }

  @Test
  @DisplayName("released blobs are indexed by when, and only those nothing points at")
  void releasedBlobsAreIndexed() throws SQLException {
    assertThat(
            DATABASE.column(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_blobs_released'"))
        .singleElement()
        .asString()
        .contains("(released_at)", "reference_count = 0");
  }
}
