package com.javadropbox.javadropbox;

import java.net.URI;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * An S3 server for tests: Adobe's S3Mock, which speaks the S3 API and keeps everything in the
 * container. Tests talk to it as the app is configured to talk to a service other than AWS, with an
 * endpoint of its own and path-style addresses.
 */
public final class S3TestSupport {

  public static final String IMAGE = "adobe/s3mock:5.2.2";

  private static final int PORT = 9090;
  // S3Mock takes any credentials, but requests are signed all the same.
  private static final String ACCESS_KEY = "test-access-key";
  private static final String SECRET_KEY = "test-secret-key";

  private S3TestSupport() {}

  /** A container to start, e.g. as a {@code @Container}. */
  public static GenericContainer<?> container() {
    return new GenericContainer<>(IMAGE)
        .withExposedPorts(PORT)
        .waitingFor(Wait.forHttp("/").forPort(PORT).forStatusCodeMatching(status -> status < 500));
  }

  public static String endpoint(GenericContainer<?> s3) {
    return "http://" + s3.getHost() + ":" + s3.getMappedPort(PORT);
  }

  /** A client for the started container, configured the way the app configures its own. */
  public static S3Client client(GenericContainer<?> s3) {
    return S3Client.builder()
        .endpointOverride(URI.create(endpoint(s3)))
        .region(Region.US_EAST_1)
        .forcePathStyle(true)
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
        .build();
  }

  /** Creates a bucket of its own for a test, and returns its name. */
  public static String createBucket(S3Client client) {
    String bucket = "test-" + UUID.randomUUID();
    client.createBucket(request -> request.bucket(bucket));
    return bucket;
  }

  /**
   * Points a Spring test context at a bucket in the container. Both are only asked for when the
   * context starts, by when the container has.
   */
  public static void register(
      DynamicPropertyRegistry registry, GenericContainer<?> s3, Supplier<String> bucket) {
    registry.add("javadropbox.storage.type", () -> "s3");
    registry.add("javadropbox.storage.s3.bucket", bucket::get);
    registry.add("javadropbox.storage.s3.endpoint", () -> endpoint(s3));
    registry.add("javadropbox.storage.s3.path-style-access", () -> "true");
    registry.add("javadropbox.storage.s3.access-key", () -> ACCESS_KEY);
    registry.add("javadropbox.storage.s3.secret-key", () -> SECRET_KEY);
  }
}
