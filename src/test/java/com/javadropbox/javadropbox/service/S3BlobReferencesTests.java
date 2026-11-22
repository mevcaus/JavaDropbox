package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.S3TestSupport;
import com.javadropbox.javadropbox.TempServingDirectory;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("Blob references in S3")
@TempServingDirectory
class S3BlobReferencesTests extends BlobReferencesContractTests {

  @Container static final GenericContainer<?> S3 = S3TestSupport.container();

  private static String bucket;

  @DynamicPropertySource
  static void storeInS3(DynamicPropertyRegistry registry) {
    S3TestSupport.register(registry, S3, S3BlobReferencesTests::bucket);
  }

  private static synchronized String bucket() {
    if (bucket == null) {
      bucket = S3TestSupport.createBucket(S3TestSupport.client(S3));
    }
    return bucket;
  }
}
