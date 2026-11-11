package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Flyway - V14 blobs")
class FlywayBlobsMigrationTests {

  private static final Database DATABASE = PostgresTestSupport.newDatabase();

  private static final String HASH =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  @BeforeAll
  static void migrate() throws SQLException {
    DATABASE.migrateTo("13");
    DATABASE.execute(
        "INSERT INTO users (username, password, role) VALUES ('ada', 'h', 'ROLE_ADMIN')");
    DATABASE.migrateTo("latest");
  }

  @Test
  @DisplayName("an upgraded install has no blobs yet, and keeps its accounts")
  void startsEmpty() throws SQLException {
    assertThat(DATABASE.column("SELECT hash FROM blobs")).isEmpty();
    assertThat(DATABASE.column("SELECT username FROM users")).containsExactly("ada");
  }

  @Test
  @DisplayName("a blob is one row per hash, with a hash, size and count that make sense")
  void blobsAreChecked() throws SQLException {
    DATABASE.execute(
        "INSERT INTO blobs (hash, size, created_at) VALUES ('" + HASH + "', 3, now())");
    try {
      assertThat(DATABASE.column("SELECT reference_count FROM blobs")).containsExactly("0");

      for (String bad :
          new String[] {
            "INSERT INTO blobs (hash, size, created_at) VALUES ('" + HASH + "', 3, now())",
            "INSERT INTO blobs (hash, size, created_at) VALUES ('ABC', 3, now())",
            "INSERT INTO blobs (hash, size, created_at) VALUES ('"
                + HASH.replace('b', 'c')
                + "', -1, now())",
            "UPDATE blobs SET reference_count = -1"
          }) {
        assertThatThrownBy(() -> DATABASE.execute(bad)).isInstanceOf(SQLException.class);
      }
    } finally {
      DATABASE.execute("DELETE FROM blobs");
    }
  }
}
