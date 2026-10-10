package com.javadropbox.javadropbox.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.service.FileStore;
import com.javadropbox.javadropbox.service.LocalFileStore;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("Storage configuration")
class StorageConfigTests {

  @TempDir Path servingDir;

  private ApplicationContextRunner context() {
    return new ApplicationContextRunner()
        .withUserConfiguration(StorageConfig.class)
        .withPropertyValues("javadropbox.serving.directory=" + servingDir);
  }

  @Test
  @DisplayName("files are kept on the local disk unless told otherwise")
  void localByDefault() {
    context()
        .run(
            started ->
                assertThat(started).getBean(FileStore.class).isInstanceOf(LocalFileStore.class));
  }

  @Test
  @DisplayName("a storage type the app does not have stops it from starting, saying which it has")
  void unknownTypeIsRefused() {
    context()
        .withPropertyValues("javadropbox.storage.type=gcs")
        .run(
            started ->
                assertThat(started)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("local or s3")
                    .hasMessageContaining("gcs"));
  }

  @Test
  @DisplayName("a storage type with a space around it is refused, as no store would match it")
  void typeWithSpacesIsRefused() {
    assertThatThrownBy(() -> new StorageConfig("s3 "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("\"s3 \"");
  }

  @Test
  @DisplayName("S3 without a bucket stops the app from starting, saying so")
  void s3NeedsABucket() {
    context()
        .withPropertyValues("javadropbox.storage.type=s3")
        .run(
            started ->
                assertThat(started)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("javadropbox.storage.s3.bucket"));
  }

  @Test
  @DisplayName("an access key without its secret stops the app from starting")
  void halfOfTheCredentialsIsRefused() {
    context()
        .withPropertyValues(
            "javadropbox.storage.type=S3",
            "javadropbox.storage.s3.bucket=files",
            "javadropbox.storage.s3.endpoint=http://localhost:1",
            "javadropbox.storage.s3.access-key=key")
        .run(
            started ->
                assertThat(started)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("access-key")
                    .hasMessageContaining("secret-key"));
  }

  @Test
  @DisplayName("a bucket that cannot be reached stops the app from starting, saying why")
  void unreachableBucketIsRefused() {
    context()
        .withPropertyValues(
            "javadropbox.storage.type=s3",
            "javadropbox.storage.s3.bucket=files",
            "javadropbox.storage.s3.endpoint=http://localhost:1",
            "javadropbox.storage.s3.access-key=key",
            "javadropbox.storage.s3.secret-key=secret")
        .run(
            started ->
                assertThat(started)
                    .getFailure()
                    .hasStackTraceContaining("Could not reach the bucket \"files\""));
  }
}
