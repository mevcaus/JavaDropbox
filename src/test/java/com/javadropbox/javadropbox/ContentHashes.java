package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Checks recorded content hashes against the bytes on disk. A hash is a hint the app only carries
 * on while the content looks unchanged since its row was written (same size, not modified since);
 * wherever it would carry one on, the hash has to be right.
 */
final class ContentHashes {

  private ContentHashes() {}

  /**
   * Fails if a file or version that looks unchanged has a hash that isn't its content's.
   *
   * @return how many hashes were checked, so a test can tell the check found something to check
   */
  static int assertTrustedHashesMatch(JdbcTemplate jdbc, Path servingDir) throws IOException {
    List<String> wrong = new ArrayList<>();
    int checked = 0;
    for (Map<String, Object> row :
        jdbc.queryForList(
            "SELECT user_id, path, size, updated_at, content_hash FROM file_metadata"
                + " WHERE content_hash IS NOT NULL")) {
      Path file =
          servingDir.resolve(".users/" + row.get("user_id")).resolve((String) row.get("path"));
      checked += check(file, row, "updated_at", wrong);
    }
    for (Map<String, Object> row :
        jdbc.queryForList(
            "SELECT stored_filename, size, created_at, content_hash FROM file_versions"
                + " WHERE content_hash IS NOT NULL")) {
      checked +=
          check(
              servingDir.resolve(".versions").resolve((String) row.get("stored_filename")),
              row,
              "created_at",
              wrong);
    }
    assertThat(wrong).as("hashes that don't match their content").isEmpty();
    return checked;
  }

  // 1 if the hash was checked, 0 if its content isn't there or looks changed.
  private static int check(
      Path file, Map<String, Object> row, String recordedAt, List<String> wrong)
      throws IOException {
    if (!Files.isRegularFile(file)) {
      return 0;
    }
    long size = ((Number) row.get("size")).longValue();
    Timestamp written = (Timestamp) row.get(recordedAt);
    boolean looksUnchanged =
        Files.size(file) == size
            && written != null
            && !Files.getLastModifiedTime(file).toInstant().isAfter(written.toInstant());
    if (!looksUnchanged) {
      return 0;
    }
    if (!row.get("content_hash").equals(sha256(file))) {
      wrong.add(file.toString());
    }
    return 1;
  }

  static String sha256(Path file) throws IOException {
    try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest())) {
      in.transferTo(java.io.OutputStream.nullOutputStream());
      return HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
    }
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
