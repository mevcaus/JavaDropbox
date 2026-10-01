package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.FileVersionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("File version service")
class FileVersionServiceTests {

  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withBean(FileVersionRepository.class, () -> mock(FileVersionRepository.class))
          .withBean(FileMetadataRepository.class, () -> mock(FileMetadataRepository.class))
          .withBean(StoragePaths.class, () -> mock(StoragePaths.class))
          .withBean(FileVersionService.class);

  @Test
  @DisplayName("a negative retention limit stops the app from starting, saying why")
  void negativeRetentionFailsStartup() {
    context
        .withPropertyValues("javadropbox.versions.max-retained=-1")
        .run(
            started ->
                assertThat(started)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("javadropbox.versions.max-retained")
                    .hasMessageContaining("-1"));
  }

  @Test
  @DisplayName("a retention limit of zero is allowed: no previous versions are kept")
  void zeroRetentionIsAllowed() {
    context
        .withPropertyValues("javadropbox.versions.max-retained=0")
        .run(started -> assertThat(started).hasNotFailed());
  }
}
