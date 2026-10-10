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
import java.nio.file.FileVisitResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
  // Listings of a few keys a request, so that every test lists across the ends of requests.
  private static final int PAGE_SIZE = 3;

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
    store = new S3FileStore(client, bucket, prefix, PART_SIZE, PAGE_SIZE);
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
    put("used/.users/1/something.txt", "already here");

    new S3FileStore(client, bucket, prefix + "fresh");
    new S3FileStore(client, bucket, prefix + "used");

    assertThat(read("fresh/" + StoragePaths.WELCOME_FILE)).isEqualTo(StoragePaths.WELCOME_TEXT);
    assertThat(exists("used/" + StoragePaths.WELCOME_FILE)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {".users/1/a.txt", ".versions/7/v1", StoragePaths.WELCOME_FILE})
  @DisplayName("a store with any sign of the app's own layout is taken as the app's")
  void storeWithTheAppsLayoutIsUsed(String key) {
    put("ours/" + key, "the app's");
    put("ours/notes.txt", "put at the top before setup");

    S3FileStore ours = new S3FileStore(client, bucket, prefix + "ours");

    assertThat(ours.description()).endsWith("/ours/");
  }

  @Test
  @DisplayName("a bucket holding someone else's objects is refused, rather than adopted")
  void someoneElsesObjectsAreRefused() {
    put("theirs/photos/2024/beach.jpg", "someone else's");

    assertThatThrownBy(() -> new S3FileStore(client, bucket, prefix + "theirs"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not JavaDropbox's")
        .hasMessageContaining("javadropbox.storage.s3.prefix");
    assertThat(exists("theirs/photos/2024/beach.jpg")).isTrue();
    assertThat(exists("theirs/" + StoragePaths.WELCOME_FILE)).isFalse();
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
    S3FileStore slashed = new S3FileStore(client, bucket, "/" + prefix + "nested//");

    assertThat(exists("nested/" + StoragePaths.WELCOME_FILE)).isTrue();
    assertThat(keys(slashed.list(""))).containsExactly(StoragePaths.WELCOME_FILE);
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
  @DisplayName("a marker for the store's own folder, as S3's console makes one, is not an item")
  void markerOfThePrefixIsNotAnItem() throws IOException {
    put("", "");
    put("docs/a.txt", "a");

    assertThat(keys(walk(""))).containsExactly("", "docs", "docs/a.txt");
    assertThat(keys(store.list(""))).containsExactly("docs");
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

  // S3 lists "clash", then "clash b/..." and "clash.txt", and only then "clash/...".
  @Test
  @DisplayName("a folder an object hides stays hidden with names listed between the two")
  void objectHidesFolderOfItsNameAcrossOtherNames() throws IOException {
    put("docs/clash", "the object");
    put("docs/clash b/inside.txt", "in a folder of a longer name");
    put("docs/clash.txt", "a file of a longer name");
    put("docs/clash/", "");
    put("docs/clash/hidden.txt", "behind the object");

    assertThat(keys(walk("docs")))
        .containsExactly(
            "docs", "docs/clash", "docs/clash b", "docs/clash b/inside.txt", "docs/clash.txt");
    assertThat(keys(store.list("docs")))
        .containsExactlyInAnyOrder("docs/clash", "docs/clash b", "docs/clash.txt");
  }

  @Test
  @DisplayName("a folder left out that runs on past a request is skipped, and what follows walked")
  void skippedFolderAcrossRequests() throws IOException {
    put("a.txt", "before");
    for (int i = 0; i < 4 * PAGE_SIZE; i++) {
      put(".git/objects/" + i, "left out");
    }
    put("z.txt", "after");

    List<String> visited = new ArrayList<>();
    store.walk(
        "",
        entry -> {
          visited.add(entry.key());
          return entry.name().startsWith(".")
              ? FileVisitResult.SKIP_SUBTREE
              : FileVisitResult.CONTINUE;
        });

    assertThat(visited).containsExactly("", ".git", "a.txt", "z.txt");
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
