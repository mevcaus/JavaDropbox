package com.javadropbox.javadropbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.MultipartConfigElement;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.util.unit.DataSize;

@DisplayName("Where uploads are kept while they arrive")
class UploadSpoolConfigTests {

  @TempDir Path dir;

  @Test
  @DisplayName("by default, in the uploads folder, which is made, with the limits kept")
  void defaultsToTheUploadsFolder() throws IOException {
    MultipartProperties properties = new MultipartProperties();
    properties.setMaxFileSize(DataSize.ofMegabytes(5));
    properties.setMaxRequestSize(DataSize.ofMegabytes(10));
    Path uploads = dir.resolve(".javadropbox/uploads");

    MultipartConfigElement config = UploadSpoolConfig.spoolIn(properties, uploads);

    assertThat(config.getLocation()).isEqualTo(uploads.toString());
    assertThat(uploads).isDirectory();
    assertThat(config.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(5).toBytes());
    assertThat(config.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(10).toBytes());
  }

  @Test
  @DisplayName("spring.servlet.multipart.location wins when it is set")
  void configuredLocationWins() throws IOException {
    MultipartProperties properties = new MultipartProperties();
    properties.setLocation(dir.resolve("elsewhere").toString());
    Path uploads = dir.resolve(".javadropbox/uploads");

    MultipartConfigElement config = UploadSpoolConfig.spoolIn(properties, uploads);

    assertThat(config.getLocation()).isEqualTo(dir.resolve("elsewhere").toString());
    assertThat(uploads).doesNotExist();
  }
}
