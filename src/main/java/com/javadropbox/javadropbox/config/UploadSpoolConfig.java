package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.service.StoragePaths;
import jakarta.servlet.MultipartConfigElement;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where Tomcat spools an upload while it arrives, before the app copies it into the store. Left to
 * itself it uses a folder under {@code java.io.tmpdir}, which in the Docker image is the
 * container's writable layer rather than the volume, so a large upload needs that much free space
 * there. Unless {@code spring.servlet.multipart.location} says otherwise, it goes in {@code
 * .javadropbox/uploads} in the serving directory instead, on the same disk as the app's other
 * state.
 */
@Configuration
public class UploadSpoolConfig {

  static final String UPLOADS_DIR = "uploads";

  // Tomcat deletes each part when its request ends; only a server that stopped mid-upload leaves
  // one behind. Nothing uploads for this long, so older ones are safe to delete, even if another
  // server shares the folder.
  static final Duration ABANDONED_AFTER = Duration.ofDays(1);

  private static final Logger log = LoggerFactory.getLogger(UploadSpoolConfig.class);

  @Bean
  public MultipartConfigElement multipartConfigElement(
      MultipartProperties properties, StoragePaths storagePaths) throws IOException {
    MultipartConfigElement configured = properties.createMultipartConfig();
    if (configured.getLocation() != null && !configured.getLocation().isBlank()) {
      return configured;
    }
    Path spool = Files.createDirectories(storagePaths.internalDir().resolve(UPLOADS_DIR));
    deleteAbandoned(spool, Instant.now().minus(ABANDONED_AFTER));
    return new MultipartConfigElement(
        spool.toString(),
        configured.getMaxFileSize(),
        configured.getMaxRequestSize(),
        configured.getFileSizeThreshold());
  }

  /**
   * Deletes the parts Tomcat spooled to {@code spool} that were last written before {@code cutoff}.
   */
  static void deleteAbandoned(Path spool, Instant cutoff) {
    try (DirectoryStream<Path> parts = Files.newDirectoryStream(spool, "upload_*.tmp")) {
      for (Path part : parts) {
        try {
          if (Files.isRegularFile(part)
              && Files.getLastModifiedTime(part).toInstant().isBefore(cutoff)) {
            Files.delete(part);
          }
        } catch (IOException e) {
          log.warn("Could not delete an abandoned upload: {}", e.toString());
        }
      }
    } catch (IOException e) {
      log.warn("Could not look for abandoned uploads: {}", e.toString());
    }
  }
}
