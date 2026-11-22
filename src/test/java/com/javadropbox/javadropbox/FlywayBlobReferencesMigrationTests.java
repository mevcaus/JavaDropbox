package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Flyway - V18 blob references")
class FlywayBlobReferencesMigrationTests {

  private static final Database DATABASE = PostgresTestSupport.newDatabase();

  private static final String HASH =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  @BeforeAll
  static void migrateFilesAndVersions() throws SQLException {
    DATABASE.migrateTo("17");
    DATABASE.execute(
        "INSERT INTO users (username, password, role) VALUES ('ada', 'h', 'ROLE_ADMIN')");
    DATABASE.execute(
        "INSERT INTO file_metadata (path, filename, size, is_directory, current_version, user_id)"
            + " SELECT 'docs', 'docs', 0, true, 1, id FROM users");
    DATABASE.execute(
        "INSERT INTO file_metadata (path, filename, size, is_directory, current_version, user_id,"
            + " content_hash) SELECT 'docs/a.txt', 'a.txt', 5, false, 2, id, '"
            + HASH
            + "' FROM users");
    DATABASE.execute(
        "INSERT INTO file_versions (file_id, version, stored_filename, size)"
            + " SELECT id, 1, id || '/v1', 3 FROM file_metadata WHERE path = 'docs/a.txt'");
    DATABASE.migrateTo("latest");
  }

  @AfterEach
  void pointAtNothing() throws SQLException {
    DATABASE.execute("UPDATE file_metadata SET blob_hash = NULL");
    DATABASE.execute("UPDATE file_versions SET blob_hash = NULL");
    DATABASE.execute("DELETE FROM blobs");
  }

  @Test
  @DisplayName("files and versions from before stay at their paths, and keep everything else")
  void existingRowsPointAtNoBlob() throws SQLException {
    assertThat(
            DATABASE.column(
                "SELECT concat_ws(',', path, size, current_version, coalesce(content_hash, '-'),"
                    + " coalesce(blob_hash, '-')) FROM file_metadata ORDER BY path"))
        .containsExactly("docs,0,1,-,-", "docs/a.txt,5,2," + HASH + ",-");
    assertThat(
            DATABASE.column(
                "SELECT concat_ws(',', version, stored_filename IS NOT NULL, size,"
                    + " coalesce(blob_hash, '-')) FROM file_versions"))
        .containsExactly("1,t,3,-");
  }

  @Test
  @DisplayName("a file or version can point only at a blob that has a row")
  void pointsOnlyAtBlobsWithRows() throws SQLException {
    assertThatThrownBy(
            () ->
                DATABASE.execute(
                    "UPDATE file_metadata SET blob_hash = '"
                        + HASH
                        + "' WHERE path = 'docs/a.txt'"))
        .isInstanceOf(SQLException.class);
    assertThatThrownBy(
            () -> DATABASE.execute("UPDATE file_versions SET blob_hash = '" + HASH + "'"))
        .isInstanceOf(SQLException.class);

    DATABASE.execute(
        "INSERT INTO blobs (hash, size, reference_count, created_at)"
            + " VALUES ('"
            + HASH
            + "', 5, 2, now())");
    DATABASE.execute(
        "UPDATE file_metadata SET blob_hash = '" + HASH + "' WHERE path = 'docs/a.txt'");
    DATABASE.execute("UPDATE file_versions SET blob_hash = '" + HASH + "'");
    assertThat(DATABASE.column("SELECT blob_hash FROM file_metadata WHERE path = 'docs/a.txt'"))
        .containsExactly(HASH);
  }

  @Test
  @DisplayName("a blob's row can't go while a file or version points at it, whatever its count")
  void referencedBlobKeepsItsRow() throws SQLException {
    DATABASE.execute(
        "INSERT INTO blobs (hash, size, reference_count, created_at)"
            + " VALUES ('"
            + HASH
            + "', 5, 0, now())");
    DATABASE.execute("UPDATE file_versions SET blob_hash = '" + HASH + "'");

    assertThatThrownBy(() -> DATABASE.execute("DELETE FROM blobs"))
        .isInstanceOf(SQLException.class);
    assertThat(DATABASE.column("SELECT hash FROM blobs")).containsExactly(HASH);
  }

  @Test
  @DisplayName("a blob's references are indexed, a file's with its owner")
  void referencesAreIndexed() throws SQLException {
    assertThat(
            DATABASE.column(
                "SELECT tablename || ': ' || substring(indexdef from '\\((.*)\\)') FROM pg_indexes"
                    + " WHERE indexname IN ('idx_file_metadata_blob_hash',"
                    + " 'idx_file_versions_blob_hash') ORDER BY tablename"))
        .containsExactly("file_metadata: blob_hash, user_id", "file_versions: blob_hash");
  }

  @Test
  @DisplayName("a folder can't point at a blob")
  void folderHasNoBlob() throws SQLException {
    DATABASE.execute(
        "INSERT INTO blobs (hash, size, reference_count, created_at)"
            + " VALUES ('"
            + HASH
            + "', 5, 1, now())");

    assertThatThrownBy(
            () ->
                DATABASE.execute(
                    "UPDATE file_metadata SET blob_hash = '" + HASH + "' WHERE path = 'docs'"))
        .isInstanceOf(SQLException.class);
  }
}
