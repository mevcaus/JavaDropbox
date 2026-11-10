package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.service.StoragePaths;
import jakarta.servlet.MultipartConfigElement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where Tomcat keeps an upload while it arrives, before the app copies it into the store. Left to
 * itself it uses a folder under {@code java.io.tmpdir}, which in the Docker image is the
 * container's writable layer rather than the volume, so a large upload needs that much free space
 * there. Unless {@code spring.servlet.multipart.location} says otherwise, it goes in {@link
 * StoragePaths#uploadsDir()} instead, on the same disk as the app's other state. Tomcat deletes
 * each part when its request ends, and makes the folder again if it was deleted; {@code
 * StorageSweeper} clears what a server that stopped mid-upload left there.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "spring.servlet.multipart",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class UploadSpoolConfig {

  @Bean
  public MultipartConfigElement multipartConfigElement(
      MultipartProperties properties, StoragePaths storagePaths) throws IOException {
    return spoolIn(properties, storagePaths.uploadsDir());
  }

  /** The multipart settings as configured, kept in {@code uploadsDir} unless they say where. */
  static MultipartConfigElement spoolIn(MultipartProperties properties, Path uploadsDir)
      throws IOException {
    MultipartConfigElement configured = properties.createMultipartConfig();
    if (!configured.getLocation().isBlank()) {
      return configured;
    }
    return new MultipartConfigElement(
        Files.createDirectories(uploadsDir).toString(),
        configured.getMaxFileSize(),
        configured.getMaxRequestSize(),
        configured.getFileSizeThreshold());
  }
}
