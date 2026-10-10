package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.FileStore;
import com.javadropbox.javadropbox.service.LooseFileAdoption;
import com.javadropbox.javadropbox.service.S3FileStore;
import com.javadropbox.javadropbox.service.SearchIndex;
import com.javadropbox.javadropbox.service.StorageSweeper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The app with its files in S3 rather than on the disk, driven through its API: what the other
 * suites prove on the disk, here for the parts that touch the store.
 */
@SpringBootTest(properties = "javadropbox.versions.max-retained=5")
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "owner")
@DisplayName("Storage in S3")
class S3StorageIntegrationTests {

  private static final Duration INDEXING = Duration.ofSeconds(30);

  @Container static final GenericContainer<?> S3 = S3TestSupport.container();

  // Only the app's own state, such as the search index, is on the disk.
  @TempDir static Path servingDir;

  private static S3Client client;
  private static String bucket;

  @DynamicPropertySource
  static void storeFilesInS3(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
    S3TestSupport.register(registry, S3, S3StorageIntegrationTests::bucket);
    // Health's details, for an admin, as in production.
    MainProperties.register(
        registry,
        "management.endpoints.web.exposure.include",
        "management.endpoint.health.show-details",
        "management.endpoint.health.roles");
  }

  private static synchronized String bucket() {
    if (bucket == null) {
      client = S3TestSupport.client(S3);
      bucket = S3TestSupport.createBucket(client);
    }
    return bucket;
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private FileMetadataRepository metadata;
  @Autowired private FileStore store;
  @Autowired private SearchIndex searchIndex;
  @Autowired private LooseFileAdoption adoption;
  @Autowired private StorageSweeper sweeper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;

  private User owner;
  // Where the account's files are in the bucket.
  private String home;

  @BeforeEach
  void setUp() {
    owner = users.save(new User("owner", "unused", "ROLE_ADMIN"));
    home = ".users/" + owner.getId() + "/";
  }

  @AfterEach
  void tearDown() throws Exception {
    TestDatabase.wipe(jdbc);
    List<ObjectIdentifier> everything =
        client.listObjectsV2Paginator(request -> request.bucket(bucket)).contents().stream()
            .map(object -> ObjectIdentifier.builder().key(object.key()).build())
            .toList();
    if (!everything.isEmpty()) {
      client.deleteObjects(
          request -> request.bucket(bucket).delete(delete -> delete.objects(everything)));
    }
    searchIndex.reconcile();
    searchIndex.awaitIdle(INDEXING);
  }

  @Test
  @DisplayName("the app runs on the S3 store, and its health says the bucket can be reached")
  void runsOnS3() throws Exception {
    assertThat(store).isInstanceOf(S3FileStore.class);
    mockMvc
        .perform(get("/actuator/health").with(user("owner").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.storage.status").value("UP"));
  }

  @Test
  @DisplayName("uploads are objects in the account's folder, listed in the tree")
  void uploadsAreObjectsInTheAccountsFolder() throws Exception {
    upload("docs/2024", "report.txt", "quarterly figures");

    assertThat(object(home + "docs/2024/report.txt")).isEqualTo("quarterly figures");
    assertThat(keys(home)).contains(home + "docs/", home + "docs/2024/");
    mockMvc
        .perform(get("/api/files"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("docs"))
        .andExpect(jsonPath("$[0].isDirectory").value(true))
        .andExpect(jsonPath("$[0].size").value(17))
        .andExpect(
            jsonPath("$[0].children[0].children[0].relativePath").value("docs/2024/report.txt"))
        .andExpect(jsonPath("$[0].children[0].children[0].id").isNotEmpty());
    // No upload's scratch object is left behind.
    assertThat(keys("")).noneMatch(key -> key.contains(".upload-"));
  }

  @Test
  @DisplayName("a file downloads whole and in ranges, and previews")
  void downloadsWholeAndInRanges() throws Exception {
    upload("", "letters.txt", "abcdefghij");

    mockMvc
        .perform(get("/api/files/download").param("path", "letters.txt"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "10"))
        .andExpect(content().string("abcdefghij"));
    mockMvc
        .perform(
            get("/api/files/download")
                .param("path", "letters.txt")
                .header(HttpHeaders.RANGE, "bytes=2-4"))
        .andExpect(status().isPartialContent())
        .andExpect(content().string("cde"));
    mockMvc
        .perform(
            get("/api/files/preview")
                .param("path", "letters.txt")
                .header(HttpHeaders.RANGE, "bytes=7-"))
        .andExpect(status().isPartialContent())
        .andExpect(content().string("hij"));
  }

  @Test
  @DisplayName("a replaced file's old content is a version in the bucket, and can be restored")
  void versionsAreKeptAndRestored() throws Exception {
    upload("", "plan.txt", "first");
    upload("", "plan.txt", "second");
    long id = metadata.findByPath(owner.getId(), "plan.txt").orElseThrow().getId();

    assertThat(object(".versions/" + id + "/v1")).isEqualTo("first");
    mockMvc
        .perform(get("/api/files/" + id + "/versions"))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].version").value(1));

    restore(id, 1, "COPY");
    assertThat(object(home + "plan_v1.txt")).isEqualTo("first");

    restore(id, 1, "OVERWRITE");
    assertThat(object(home + "plan.txt")).isEqualTo("first");
    assertThat(object(".versions/" + id + "/v2")).isEqualTo("second");
  }

  @Test
  @DisplayName("a folder downloads as a zip of what it holds")
  void folderDownloadsAsZip() throws Exception {
    upload("project", "README.md", "readme");
    upload("project/src", "Main.java", "class Main {}");
    createFolder("project", "empty");

    byte[] zip =
        mockMvc
            .perform(get("/api/files/download").param("path", "project"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    assertThat(unzip(zip))
        .containsOnly(
            Map.entry("project/", ""),
            Map.entry("project/README.md", "readme"),
            Map.entry("project/empty/", ""),
            Map.entry("project/src/", ""),
            Map.entry("project/src/Main.java", "class Main {}"));
  }

  @Test
  @DisplayName("deleting a folder deletes its objects and their versions, and its folder stays")
  void deletingAFolderDeletesItsObjects() throws Exception {
    upload("outer/inner", "a.txt", "one");
    upload("outer/inner", "a.txt", "two");
    long id = metadata.findByPath(owner.getId(), "outer/inner/a.txt").orElseThrow().getId();

    mockMvc
        .perform(delete("/api/files").param("path", "outer/inner").with(csrf()))
        .andExpect(status().isOk());

    assertThat(keys(home + "outer/inner")).isEmpty();
    assertThat(keys(".versions/" + id + "/")).isEmpty();
    assertThat(keys(home + "outer/")).containsExactly(home + "outer/");
    mockMvc
        .perform(get("/api/files"))
        .andExpect(jsonPath("$[0].name").value("outer"))
        .andExpect(jsonPath("$[0].children").isEmpty());
  }

  @Test
  @DisplayName("an empty folder is a marker object, listed in the tree")
  void emptyFolderIsListed() throws Exception {
    createFolder("", "Empty");

    assertThat(keys(home + "Empty")).containsExactly(home + "Empty/");
    mockMvc
        .perform(get("/api/files"))
        .andExpect(jsonPath("$[0].name").value("Empty"))
        .andExpect(jsonPath("$[0].isDirectory").value(true));
  }

  @Test
  @DisplayName("share links open files and folders kept in S3, signed out")
  void shareLinksOpen() throws Exception {
    upload("shared", "note.txt", "for anyone with the link");

    mockMvc
        .perform(get("/share/" + share("shared/note.txt") + "/download").with(anonymous()))
        .andExpect(status().isOk())
        .andExpect(content().string("for anyone with the link"));
    mockMvc
        .perform(get("/share/" + share("shared") + "/info").with(anonymous()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isDirectory").value(true))
        .andExpect(jsonPath("$.contents[0].name").value("note.txt"));
  }

  @Test
  @DisplayName("search finds files by the text in them, read from S3")
  void searchReadsTextFromS3() throws Exception {
    upload("notes", "plan.txt", "The quarterly budget is due on Friday.");
    upload("notes", "other.txt", "Nothing to see here.");

    JsonNode answer = search("budget");

    assertThat(answer.path("total").asLong()).isEqualTo(1);
    assertThat(answer.path("results").get(0).path("relativePath").asText())
        .isEqualTo("notes/plan.txt");
    assertThat(answer.path("results").get(0).path("snippet").path("text").asText())
        .contains("quarterly budget");
  }

  @Test
  @DisplayName("files another tool put in the bucket show up in the tree, downloads and search")
  void filesPutByOtherToolsShowUp() throws Exception {
    put(home + "imported/2023/minutes.txt", "minutes of the annual meeting");

    mockMvc
        .perform(get("/api/files"))
        .andExpect(jsonPath("$[0].name").value("imported"))
        .andExpect(jsonPath("$[0].ownerName").value("system"))
        .andExpect(jsonPath("$[0].lastModified").isNotEmpty())
        .andExpect(jsonPath("$[0].children[0].children[0].name").value("minutes.txt"));
    mockMvc
        .perform(get("/api/files/download").param("path", "imported/2023/minutes.txt"))
        .andExpect(content().string("minutes of the annual meeting"));

    searchIndex.reconcile();
    searchIndex.awaitIdle(INDEXING);
    assertThat(search("annual").path("results").get(0).path("relativePath").asText())
        .isEqualTo("imported/2023/minutes.txt");
  }

  @Test
  @DisplayName("storage use counts the account's objects and their versions")
  void usageCountsObjectsAndVersions() throws Exception {
    upload("", "a.txt", "12345");
    upload("", "a.txt", "1234567890");
    upload("docs", "b.txt", "123");

    mockMvc
        .perform(get("/api/storage"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.usedBytes").value(5 + 10 + 3));
  }

  @Test
  @DisplayName("a quota is enforced against what the bucket holds")
  void quotaIsEnforced() throws Exception {
    owner.setQuotaBytes(10L);
    users.save(owner);
    upload("", "fits.txt", "12345");

    mockMvc
        .perform(
            multipart("/api/files")
                .file(file("too-much.txt", "1234567890"))
                .param("path", "")
                .with(csrf().asHeader()))
        .andExpect(status().isInsufficientStorage());
    // The account's folder, and the one file that fitted.
    assertThat(keys(home)).containsExactlyInAnyOrder(home, home + "fits.txt");
  }

  @Test
  @DisplayName("another account sees none of it")
  void anotherAccountSeesNothing() throws Exception {
    upload("", "mine.txt", "private");
    users.save(new User("other", "unused", "ROLE_USER"));

    mockMvc.perform(get("/api/files").with(user("other"))).andExpect(content().json("[]"));
    mockMvc
        .perform(get("/api/files/download").param("path", "mine.txt").with(user("other")))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("what is at the top of the bucket is the first account's, as on the disk")
  void topOfTheBucketIsAdopted() throws Exception {
    put("Welcome to JavaDropbox.txt", "welcome");
    put("old-folder/notes.txt", "from before");

    adoption.adopt(owner);

    assertThat(object(home + "Welcome to JavaDropbox.txt")).isEqualTo("welcome");
    assertThat(object(home + "old-folder/notes.txt")).isEqualTo("from before");
    // Nothing is left at the top: every object is in the accounts' folders.
    assertThat(keys("")).allMatch(key -> key.startsWith(".users/"));
  }

  @Test
  @DisplayName("the startup sweep removes an empty version folder of no known file, and no more")
  void sweepRemovesOnlyLeftovers() throws Exception {
    upload("", "kept.txt", "one");
    upload("", "kept.txt", "two");
    long id = metadata.findByPath(owner.getId(), "kept.txt").orElseThrow().getId();
    put(".versions/999999/", "");
    put(".versions/999998/v1", "a version of a file this database does not know");

    sweeper.sweep();

    assertThat(keys(".versions/999999/")).isEmpty();
    assertThat(keys(".versions/999998/")).hasSize(1);
    assertThat(object(".versions/" + id + "/v1")).isEqualTo("one");
    assertThat(object(home + "kept.txt")).isEqualTo("two");
  }

  @Test
  @DisplayName("a store written through the API can be read back through the store")
  void storeAndApiAgree() throws Exception {
    store.createFolders(home + "direct");
    store.write(
        home + "direct/file.txt",
        new ByteArrayInputStream("direct".getBytes(StandardCharsets.UTF_8)),
        6);

    mockMvc
        .perform(get("/api/files/download").param("path", "direct/file.txt"))
        .andExpect(content().string("direct"));
  }

  // --- helpers ---------------------------------------------------------------------------------

  private void upload(String folder, String name, String text) throws Exception {
    mockMvc
        .perform(
            multipart("/api/files")
                .file(file(name, text))
                .param("path", folder)
                .with(csrf().asHeader()))
        .andExpect(status().isOk());
    searchIndex.awaitIdle(INDEXING);
  }

  private static MockMultipartFile file(String name, String text) {
    return new MockMultipartFile(
        "files", name, "application/octet-stream", text.getBytes(StandardCharsets.UTF_8));
  }

  private void createFolder(String parent, String name) throws Exception {
    mockMvc
        .perform(post("/api/folders").param("path", parent).param("name", name).with(csrf()))
        .andExpect(status().isOk());
  }

  private void restore(long id, int version, String mode) throws Exception {
    mockMvc
        .perform(
            post("/api/files/" + id + "/versions/" + version + "/restore")
                .param("mode", mode)
                .with(csrf()))
        .andExpect(status().isOk());
  }

  private String share(String path) throws Exception {
    String body =
        mockMvc
            .perform(post("/api/share").param("path", path).with(csrf()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String url = json.readTree(body).get("url").asText();
    return url.substring(url.lastIndexOf("/share/") + "/share/".length());
  }

  private JsonNode search(String q) throws Exception {
    return json.readTree(
        mockMvc
            .perform(get("/api/search").param("q", q))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8));
  }

  private static void put(String key, String content) {
    client.putObject(
        request -> request.bucket(bucket).key(key),
        RequestBody.fromString(content, StandardCharsets.UTF_8));
  }

  private static String object(String key) {
    return client
        .getObjectAsBytes(request -> request.bucket(bucket).key(key))
        .asString(StandardCharsets.UTF_8);
  }

  private static List<String> keys(String prefix) {
    return client
        .listObjectsV2Paginator(request -> request.bucket(bucket).prefix(prefix))
        .contents()
        .stream()
        .map(S3Object::key)
        .toList();
  }

  private static Map<String, String> unzip(byte[] zip) throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
        entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return entries;
  }
}
