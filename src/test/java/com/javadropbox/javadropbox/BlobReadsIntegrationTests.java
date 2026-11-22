package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.FileStore;
import com.javadropbox.javadropbox.service.SearchIndex;
import com.javadropbox.javadropbox.service.StoragePaths;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Content a row says is kept as a blob (#316) is read from the blob, by every route that reads
 * content. Nothing makes rows point at blobs yet, so each test moves an uploaded file's content
 * into one by hand, and removes it from its own path, so that only the blob has it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Reading content kept as a blob")
@TempServingDirectory
class BlobReadsIntegrationTests {

  private static final String LIVE = "the live content, from its blob";
  private static final String FIRST = "version one, from its blob";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StoragePaths storagePaths;
  @Autowired private FileStore store;
  @Autowired private ObjectMapper json;
  @Autowired private SearchIndex searchIndex;

  private User owner;

  @BeforeEach
  void setUp() throws Exception {
    owner = users.save(new User("owner", "unused", "ROLE_USER"));
    // Twice, so the first is kept as version 1.
    upload("docs", "a.txt", "first at its path");
    upload("docs", "a.txt", "second at its path");

    String live = keepAsBlob(LIVE);
    jdbc.update("UPDATE file_metadata SET blob_hash = ? WHERE path = 'docs/a.txt'", live);
    store.delete(storagePaths.home(owner).key() + "/docs/a.txt");

    String first = keepAsBlob(FIRST);
    jdbc.update(
        "UPDATE file_versions SET blob_hash = ?, owner_id = ? WHERE version = 1",
        first,
        owner.getId());
    store.delete(
        StoragePaths.VERSIONS_DIR
            + "/"
            + jdbc.queryForObject("SELECT stored_filename FROM file_versions", String.class));
  }

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  @Test
  @DisplayName("a download reads the blob")
  void downloadReadsTheBlob() throws Exception {
    mockMvc
        .perform(signedIn(get("/api/files/download").param("path", "docs/a.txt")))
        .andExpect(status().isOk())
        .andExpect(content().string(LIVE));
  }

  @Test
  @DisplayName("a preview reads the blob")
  void previewReadsTheBlob() throws Exception {
    mockMvc
        .perform(signedIn(get("/api/files/preview").param("path", "docs/a.txt")))
        .andExpect(status().isOk())
        .andExpect(content().string(LIVE));
  }

  @Test
  @DisplayName("a folder zip reads the blob")
  void folderZipReadsTheBlob() throws Exception {
    byte[] zip =
        mockMvc
            .perform(signedIn(get("/api/files/download").param("path", "docs")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    assertThat(entries(zip)).containsEntry("docs/a.txt", LIVE);
  }

  @Test
  @DisplayName("a share link reads the blob")
  void shareLinkReadsTheBlob() throws Exception {
    String body =
        mockMvc
            .perform(
                signedIn(
                    post("/api/share")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("path", "docs/a.txt")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String url = json.readTree(body).get("url").asText();

    mockMvc
        .perform(get(url.substring(url.indexOf("/share/"))).with(anonymous()))
        .andExpect(status().isOk())
        .andExpect(content().string(LIVE));
  }

  @Test
  @DisplayName("restoring a version kept as a blob copies the blob")
  void restoreReadsTheVersionsBlob() throws Exception {
    Long id = jdbc.queryForObject("SELECT file_id FROM file_versions", Long.class);

    mockMvc
        .perform(signedIn(post("/api/files/" + id + "/versions/1/restore").param("mode", "COPY")))
        .andExpect(status().isOk());

    mockMvc
        .perform(signedIn(get("/api/files/download").param("path", "docs/a_v1.txt")))
        .andExpect(status().isOk())
        .andExpect(content().string(FIRST));
  }

  @Test
  @DisplayName("search reads the text of the blob")
  void searchReadsTheBlob() throws Exception {
    // Changed as the row sees it, so the search index reads the file again.
    jdbc.update(
        "UPDATE file_metadata SET size = ?, updated_at = now() WHERE path = 'docs/a.txt'",
        LIVE.length());
    searchIndex.reconcile();
    searchIndex.awaitIdle(Duration.ofSeconds(30));

    String body =
        mockMvc
            .perform(signedIn(get("/api/search").param("q", "blob")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(body).get("results").findValuesAsText("relativePath"))
        .containsExactly("docs/a.txt");
  }

  @Test
  @DisplayName("each account reads its own file at a path, and only its own row's blob")
  void lookupIsPerAccount() throws Exception {
    users.save(new User("other", "unused", "ROLE_USER"));
    mockMvc
        .perform(
            multipart("/api/files")
                .file(
                    new MockMultipartFile(
                        "files",
                        "a.txt",
                        "text/plain",
                        "the other account's own".getBytes(StandardCharsets.UTF_8)))
                .param("path", "docs")
                .with(user("other"))
                .with(csrf().asHeader()))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            get("/api/files/download").param("path", "docs/a.txt").with(user("other")).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(content().string("the other account's own"));
    mockMvc
        .perform(signedIn(get("/api/files/download").param("path", "docs/a.txt")))
        .andExpect(content().string(LIVE));
  }

  @Test
  @DisplayName("a file whose blob has gone is not found, rather than read from its path")
  void missingBlobIsNotFound() throws Exception {
    // Something at the path again, which the row doesn't say is its content.
    store.write(
        storagePaths.home(owner).key() + "/docs/a.txt",
        new ByteArrayInputStream("stray".getBytes(StandardCharsets.UTF_8)),
        5);
    String live =
        jdbc.queryForObject(
            "SELECT blob_hash FROM file_metadata WHERE path = 'docs/a.txt'", String.class);
    store.delete(StoragePaths.BLOBS_DIR + "/" + live.substring(0, 2) + "/" + live);

    mockMvc
        .perform(signedIn(get("/api/files/download").param("path", "docs/a.txt")))
        .andExpect(status().isNotFound());
  }

  // Puts content in the store as the blob of its SHA-256, with a row, and returns the hash.
  private String keepAsBlob(String content) throws Exception {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    String folder = StoragePaths.BLOBS_DIR + "/" + hash.substring(0, 2);
    store.createFolders(folder);
    store.write(folder + "/" + hash, new ByteArrayInputStream(bytes), bytes.length);
    jdbc.update(
        "INSERT INTO blobs (hash, size, reference_count, created_at) VALUES (?, ?, 1, now())",
        hash,
        bytes.length);
    return hash;
  }

  private void upload(String folder, String name, String content) throws Exception {
    mockMvc
        .perform(
            signedIn(
                multipart("/api/files")
                    .file(
                        new MockMultipartFile(
                            "files", name, "text/plain", content.getBytes(StandardCharsets.UTF_8)))
                    .param("path", folder)))
        .andExpect(status().isOk());
  }

  private MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
    return request.with(user("owner")).with(csrf().asHeader());
  }

  private static Map<String, String> entries(byte[] zip) throws IOException {
    Map<String, String> entries = new HashMap<>();
    try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
      for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
        entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return entries;
  }
}
