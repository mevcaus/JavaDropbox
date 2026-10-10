package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.service.LocalFileStore;
import com.javadropbox.javadropbox.service.S3FileStore;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Where files are stored, as {@code javadropbox.storage.type} says: {@code local} (the default),
 * the serving directory on the server's disk, or {@code s3}, a bucket in S3 or a service with the
 * same API, set up by the {@code javadropbox.storage.s3.*} settings (see docs/self-hosting.md).
 */
@Configuration
public class StorageConfig {

  static final String TYPE = "javadropbox.storage.type";
  private static final String S3 = "javadropbox.storage.s3.";

  private static final Set<String> TYPES = Set.of("local", "s3");

  private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

  // Checked here, rather than leaving the app to fail for want of a store, so the error says why.
  // Matched as the beans below match it: in any letter case, but without spaces around it.
  public StorageConfig(@Value("${" + TYPE + ":local}") String type) {
    if (!TYPES.contains(type.toLowerCase(Locale.ROOT))) {
      throw new IllegalStateException(TYPE + " must be local or s3, not \"" + type + "\"");
    }
  }

  @Bean
  @ConditionalOnProperty(name = TYPE, havingValue = "local", matchIfMissing = true)
  public LocalFileStore localFileStore(@Value("${javadropbox.serving.directory}") String directory)
      throws IOException {
    return new LocalFileStore(Path.of(directory));
  }

  /**
   * @param endpoint the service's URL, for anything but AWS itself
   * @param region needed by AWS; with an endpoint of its own, a service rarely cares
   * @param accessKey with {@code secretKey}; without them, the AWS SDK looks for credentials where
   *     it always does, such as {@code AWS_ACCESS_KEY_ID} or the instance's role
   * @param maxConnections each request being served reads or writes at most one object at a time
   */
  @Bean
  @ConditionalOnProperty(name = TYPE, havingValue = "s3")
  public S3FileStore s3FileStore(
      @Value("${" + S3 + "bucket:}") String bucket,
      @Value("${" + S3 + "prefix:}") String prefix,
      @Value("${" + S3 + "endpoint:}") String endpoint,
      @Value("${" + S3 + "region:}") String region,
      @Value("${" + S3 + "path-style-access:false}") boolean pathStyleAccess,
      @Value("${" + S3 + "access-key:}") String accessKey,
      @Value("${" + S3 + "secret-key:}") String secretKey,
      @Value("${server.tomcat.threads.max:200}") int maxConnections) {
    if (bucket.isBlank()) {
      throw new IllegalStateException(
          TYPE + " is s3, so " + S3 + "bucket has to say which bucket to store files in");
    }
    S3ClientBuilder builder =
        S3Client.builder()
            .httpClientBuilder(Apache5HttpClient.builder().maxConnections(maxConnections + 10))
            .credentialsProvider(credentials(accessKey.strip(), secretKey.strip()))
            .forcePathStyle(pathStyleAccess);
    if (!endpoint.isBlank()) {
      builder.endpointOverride(URI.create(endpoint.strip()));
      // Checksums on every request are recent in S3, and not every service that speaks its API
      // accepts them; those it requires are still sent.
      builder.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED);
      builder.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
    }
    if (!region.isBlank()) {
      builder.region(Region.of(region.strip()));
    } else if (!endpoint.isBlank()) {
      // Requests are still signed for a region, which such a service mostly ignores.
      builder.region(Region.US_EAST_1);
    }
    // Otherwise the SDK's own search: AWS_REGION, the AWS config file, the instance's region.

    S3Client client;
    try {
      client = builder.build();
    } catch (SdkException e) {
      throw new IllegalStateException(
          "Could not set up S3 storage: "
              + e.getMessage()
              + " Set "
              + S3
              + "region, or AWS_REGION.",
          e);
    }
    try {
      S3FileStore store = new S3FileStore(client, bucket.strip(), prefix);
      log.info(
          "Storing files in {}{}",
          store.description(),
          endpoint.isBlank() ? "" : " at " + endpoint.strip());
      return store;
    } catch (RuntimeException e) {
      client.close();
      throw e;
    }
  }

  /**
   * The {@code storage} component of {@code /actuator/health}: whether the bucket can be reached,
   * as {@code db} says whether the database can. On the disk, {@code diskSpace} covers it.
   */
  @Bean
  @ConditionalOnProperty(name = TYPE, havingValue = "s3")
  public HealthIndicator storageHealthIndicator(S3FileStore store) {
    return () -> {
      try {
        store.check();
        return Health.up().withDetail("store", store.description()).build();
      } catch (IllegalStateException e) {
        return Health.down(e).withDetail("store", store.description()).build();
      }
    };
  }

  private static AwsCredentialsProvider credentials(String accessKey, String secretKey) {
    if (accessKey.isEmpty() && secretKey.isEmpty()) {
      return DefaultCredentialsProvider.builder().build();
    }
    if (accessKey.isEmpty() || secretKey.isEmpty()) {
      throw new IllegalStateException(
          "Set both " + S3 + "access-key and " + S3 + "secret-key, or neither");
    }
    return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
  }
}
