package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.S3TestSupport;
import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.service.FileStore.Entry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

@Testcontainers
@DisplayName("S3 file store")
class S3FileStoreTests extends FileStoreContractTests {

  // Parts as small as S3 takes them, so a multipart upload needs only a few megabytes.
  private static final long PART_SIZE = 5L * 1024 * 1024;

  @Container static final GenericContainer<?> S3 = S3TestSupport.container();

  private static S3Client client;
  private static String bucket;

  // Each test has a prefix of its own in the bucket, which also proves nothing outside it shows.
  private String prefix;
  private S3FileStore store;

  @BeforeAll
  static void createBucket() {
    client = S3TestSupport.client(S3);
    bucket = S3TestSupport.createBucket(client);
  }

  @AfterAll
  static void closeClient() {
    client.close();
  }

  @BeforeEach
  void setUp() {
    prefix = "test-" + UUID.randomUUID() + "/";
    store = new S3FileStore(client, bucket, prefix, PART_SIZE);
    // A new store is given a welcome file, which the tests of what a store does do not expect.
    client.deleteObject(request -> request.bucket(bucket).key(prefix + StoragePaths.WELCOME_FILE));
  }

  @Override
  protected FileStore store() {
    return store;
  }

  @Test
  @DisplayName("an empty store gets a welcome file, one with anything in it does not")
  void emptyStoreGetsAWelcomeFile() throws IOException {
    put("used/something.txt", "already here");

    new S3FileStore(client, bucket, prefix + "fresh");
    new S3FileStore(client, bucket, prefix + "used");

    assertThat(read("fresh/" + StoragePaths.WELCOME_FILE)).isEqualTo(StoragePaths.WELCOME_TEXT);
    assertThat(exists("used/" + StoragePaths.WELCOME_FILE)).isFalse();
  }

  @Test
  @DisplayName("a bucket that does not exist stops the app from starting, saying so")
  void missingBucketIsRefused() {
    assertThatThrownBy(() -> new S3FileStore(client, "no-such-bucket-" + UUID.randomUUID(), ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not exist");
  }

  @Test
  @DisplayName("the prefix is a folder whatever slashes it is given with")
  void prefixIsNormalized() throws IOException {
    put("nested/a.txt", "a");

    S3FileStore slashed = new S3FileStore(client, bucket, "/" + prefix + "nested//");

    assertThat(keys(slashed.list(""))).containsExactly("a.txt");
  }

  @Test
  @DisplayName("objects another tool wrote, with no folder markers, are files in folders")
  void objectsWithoutMarkersAreInFolders() throws IOException {
    put("docs/2024/report.txt", "report");
    put("docs/notes.txt", "notes");

    assertThat(store.stat("docs").orElseThrow().isDirectory()).isTrue();
    assertThat(store.stat("docs").orElseThrow().modified()).isNull();
    assertThat(keys(walk("")))
        .containsExactly("", "docs", "docs/2024", "docs/2024/report.txt", "docs/notes.txt");
    assertThat(keys(store.list("docs"))).containsExactlyInAnyOrder("docs/2024", "docs/notes.txt");
  }

  @Test
  @DisplayName("deleting the only object in a folder with no marker leaves the folder")
  void deletingTheLastObjectOfAnUnmarkedFolderKeepsIt() throws IOException {
    put("docs/sub/only.txt", "only");

    store.deleteRecursively("docs/sub/only.txt");

    assertThat(store.stat("docs/sub").orElseThrow().isDirectory()).isTrue();
    assertThat(exists("docs/sub/")).isTrue();
  }

  @Test
  @DisplayName("a folder that an object of the same name hides is left out")
  void objectHidesFolderOfItsName() throws IOException {
    put("docs/a.txt", "a");
    put("clash", "the object");
    put("clash/hidden.txt", "behind the object");

    assertThat(keys(walk(""))).containsExactly("", "clash", "docs", "docs/a.txt");
    assertThat(store.stat("clash").orElseThrow().isDirectory()).isFalse();
    assertThat(keys(store.list(""))).containsExactlyInAnyOrder("clash", "docs");
  }

  // Real S3 takes such keys from any tool, though the S3 server in these tests refuses them.
  @ParameterizedTest
  @ValueSource(strings = {"docs//a.txt", "docs/./a.txt", "docs/../a.txt", "docs/", "/docs"})
  @DisplayName("keys no path could have are not items")
  void keysNoPathCouldHaveAreNotItems(String key) {
    assertThat(S3FileStore.isItemKey(key, "")).isFalse();
    assertThat(S3FileStore.isItemKey("docs/a.txt", "")).isTrue();
    assertThat(S3FileStore.isItemKey("docs/a.txt", "docs")).isTrue();
  }

  @Test
  @DisplayName("a large file goes up in parts and comes back whole")
  void largeFileIsWrittenInParts() throws IOException {
    byte[] content = new byte[(int) (2 * PART_SIZE + 1234)];
    for (int i = 0; i < content.length; i++) {
      content[i] = (byte) (i * 31);
    }
    store.createFolders("big");

    long written = store.write("big/file.bin", new ByteArrayInputStream(content), content.length);

    assertThat(written).isEqualTo(content.length);
    try (InputStream in = store.open("big/file.bin")) {
      assertThat(Arrays.equals(in.readAllBytes(), content)).isTrue();
    }
    assertThat(
            client
                .headObject(request -> request.bucket(bucket).key(prefix + "big/file.bin"))
                .eTag())
        .as("an object uploaded in parts has an ETag ending in its number of parts")
        .endsWith("-3\"");
  }

  @Test
  @DisplayName("a range is read from where it starts, without reading what comes before it")
  void rangeStartsWhereAsked() throws IOException {
    write("a.txt", "0123456789");
    Resource resource = store.resource(store.stat("a.txt").orElseThrow());

    try (InputStream in = resource.getInputStream()) {
      assertThat(in.skip(7)).isEqualTo(7);
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("789");
    }
    try (InputStream in = resource.getInputStream()) {
      assertThat(in.skip(100)).isEqualTo(10);
      assertThat(in.read()).isEqualTo(-1);
    }
  }

  @Test
  @DisplayName("closing a download part-way leaves the store working")
  void closingEarlyIsFine() throws IOException {
    byte[] content = new byte[1024 * 1024];
    store.write("a.bin", new ByteArrayInputStream(content), content.length);

    for (int i = 0; i < 100; i++) {
      try (InputStream in = store.open("a.bin")) {
        assertThat(in.read()).isZero();
      }
    }

    assertThat(store.stat("a.bin").orElseThrow().size()).isEqualTo(content.length);
  }

  @Test
  @DisplayName("a file that changed size since it was looked at is not sent with the old length")
  void changedFileIsNotSentWithTheOldLength() throws IOException {
    write("a.txt", "short");
    Entry before = store.stat("a.txt").orElseThrow();
    write("a.txt", "much longer now");

    Resource resource = store.resource(before);

    assertThatThrownBy(() -> resource.getInputStream().read())
        .isInstanceOf(IOException.class)
        .hasMessageContaining("changed");
  }

  @Test
  @DisplayName("a key longer than S3 takes is refused as a bad request")
  void overlongKeyIsRefused() {
    String key = "a/".repeat(S3FileStore.MAX_KEY_BYTES / 2) + "file.txt";

    assertThatThrownBy(() -> store.stat(key)).isInstanceOf(BadRequestException.class);
  }

  private void put(String key, String content) {
    client.putObject(
        request -> request.bucket(bucket).key(prefix + key),
        RequestBody.fromString(content, StandardCharsets.UTF_8));
  }

  // Straight from the bucket, for what the store would not show.
  private boolean exists(String key) {
    return !client
        .listObjectsV2(request -> request.bucket(bucket).prefix(prefix + key).maxKeys(1))
        .contents()
        .isEmpty();
  }
}
