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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.SearchIndex;
import com.javadropbox.javadropbox.service.StoragePaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.FileSystemUtils;

/**
 * Two accounts, each with files of its own: neither can reach the other's, by path or by id,
 * through any endpoint. Alice's file {@code secret.txt} is the target throughout; Bob tries every
 * way in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Accounts' files are their own")
class AccountIsolationIntegrationTests {

  private static final RequestPostProcessor ALICE = user("alice");
  private static final RequestPostProcessor BOB = user("bob");

  @TempDir static Path servingDir;

  @DynamicPropertySource
  static void overrideServingDirectory(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private StoragePaths storagePaths;
  @Autowired private SearchIndex searchIndex;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;

  private User alice;
  private User bob;

  @BeforeEach
  void setUp() throws Exception {
    alice = users.save(new User("alice", "unused", User.ROLE_USER));
    bob = users.save(new User("bob", "unused", User.ROLE_USER));
    upload(ALICE, "secret.txt", "alice's secret plans");
    upload(ALICE, "secret.txt", "alice's newer secret plans");
  }

  @AfterEach
  void tearDown() throws Exception {
    TestDatabase.wipe(jdbc);
    try (var entries = Files.list(servingDir)) {
      for (Path entry : entries.toList()) {
        FileSystemUtils.deleteRecursively(entry);
      }
    }
    searchIndex.reconcile();
    searchIndex.awaitIdle(Duration.ofSeconds(30));
  }

  @Test
  @DisplayName("each account's files are in a folder of its own on disk")
  void filesAreInTheAccountsFolder() {
    assertThat(storagePaths.home(alice).root().resolve("secret.txt"))
        .hasContent("alice's newer secret plans");
    assertThat(storagePaths.home(bob).root().resolve("secret.txt")).doesNotExist();
  }

  @Test
  @DisplayName("listing shows only the account's own files")
  void listingIsPrivate() throws Exception {
    mockMvc
        .perform(get("/api/files").with(BOB))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
    mockMvc
        .perform(get("/api/files").with(ALICE))
        .andExpect(jsonPath("$[0].name").value("secret.txt"));
  }

  @Test
  @DisplayName("two accounts can each have a file at a path, with its own id, versions and links")
  void samePathInTwoAccounts() throws Exception {
    long alicesId = idOf(ALICE, "secret.txt");
    String alicesLink = share(ALICE, "secret.txt");

    upload(BOB, "secret.txt", "bob's own");

    mockMvc
        .perform(get("/api/files/download").param("path", "secret.txt").with(BOB))
        .andExpect(content().string("bob's own"));
    mockMvc
        .perform(get("/api/files/download").param("path", "secret.txt").with(ALICE))
        .andExpect(content().string("alice's newer secret plans"));

    // Bob's file is a row of his own, not Alice's taken over.
    JsonNode bobsFile = node(BOB, "secret.txt");
    assertThat(bobsFile.get("id").asLong()).isNotEqualTo(alicesId);
    assertThat(bobsFile.get("ownerName").asText()).isEqualTo("bob");
    assertThat(node(ALICE, "secret.txt").get("id").asLong()).isEqualTo(alicesId);
    assertThat(node(ALICE, "secret.txt").get("ownerName").asText()).isEqualTo("alice");

    // Alice keeps her previous version and her link, which still opens her file.
    mockMvc
        .perform(get("/api/files/" + alicesId + "/versions").with(ALICE))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].version").value(1));
    mockMvc
        .perform(get("/share/" + alicesLink).with(anonymous()))
        .andExpect(status().isOk())
        .andExpect(content().string("alice's newer secret plans"));

    // A link Bob makes to his file at the same path opens his.
    mockMvc
        .perform(get("/share/" + share(BOB, "secret.txt")).with(anonymous()))
        .andExpect(status().isOk())
        .andExpect(content().string("bob's own"));
    mockMvc
        .perform(get("/share/" + alicesLink).with(anonymous()))
        .andExpect(content().string("alice's newer secret plans"));
  }

  @Test
  @DisplayName("deleting a folder leaves the one another account has at the same path untouched")
  void deleteLeavesTheSamePathInAnotherAccount() throws Exception {
    upload(ALICE, "docs", "a.txt", "alice's draft");
    upload(ALICE, "docs", "a.txt", "alice's final");
    upload(BOB, "docs", "a.txt", "bob's draft");
    upload(BOB, "docs", "a.txt", "bob's final");
    long alicesId = idOf(ALICE, "docs/a.txt");
    long bobsId = idOf(BOB, "docs/a.txt");
    String alicesLink = share(ALICE, "docs/a.txt");
    Path versions = storagePaths.versionsDir();
    assertThat(versions.resolve(bobsId + "/v1")).hasContent("bob's draft");

    mockMvc
        .perform(delete("/api/files").param("path", "docs").with(BOB).with(csrf()))
        .andExpect(status().isOk());

    // Bob's folder and its versions are gone ...
    assertThat(storagePaths.home(bob).root().resolve("docs")).doesNotExist();
    assertThat(versions.resolve(String.valueOf(bobsId))).doesNotExist();
    // ... and Alice's file, version and link are all still there.
    mockMvc
        .perform(get("/api/files/download").param("path", "docs/a.txt").with(ALICE))
        .andExpect(status().isOk())
        .andExpect(content().string("alice's final"));
    mockMvc
        .perform(get("/api/files/" + alicesId + "/versions").with(ALICE))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].version").value(1));
    assertThat(versions.resolve(alicesId + "/v1")).hasContent("alice's draft");
    mockMvc
        .perform(get("/share/" + alicesLink).with(anonymous()))
        .andExpect(status().isOk())
        .andExpect(content().string("alice's final"));
  }

  @Test
  @DisplayName("another account's file cannot be downloaded, by its path or by a way out")
  void downloadIsPrivate() throws Exception {
    mockMvc
        .perform(get("/api/files/download").param("path", "secret.txt").with(BOB))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            get("/api/files/download")
                .param("path", "../" + alice.getId() + "/secret.txt")
                .with(BOB))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            get("/api/files/download")
                .param("path", "../../.users/" + alice.getId() + "/secret.txt")
                .with(BOB))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("another account's file cannot be previewed")
  void previewIsPrivate() throws Exception {
    mockMvc
        .perform(get("/api/files/preview").param("path", "secret.txt").with(BOB))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            get("/api/files/preview")
                .param("path", "../" + alice.getId() + "/secret.txt")
                .with(BOB))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("another account's file cannot be shared, nor its links listed or revoked by id")
  void sharingIsPrivate() throws Exception {
    mockMvc
        .perform(post("/api/share").param("path", "secret.txt").with(BOB).with(csrf()))
        .andExpect(status().isNotFound());

    String token = share(ALICE, "secret.txt");
    mockMvc
        .perform(get("/api/share").param("path", "secret.txt").with(BOB))
        .andExpect(content().json("[]"));
    long linkId =
        json.readTree(
                mockMvc
                    .perform(get("/api/share").param("path", "secret.txt").with(ALICE))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get(0)
            .get("id")
            .asLong();

    mockMvc
        .perform(delete("/api/share/" + linkId).with(BOB).with(csrf()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/share/" + token).with(anonymous()))
        .andExpect(status().isOk())
        .andExpect(content().string("alice's newer secret plans"));
  }

  @Test
  @DisplayName("another account's file's versions cannot be listed or restored by its id")
  void versionsArePrivate() throws Exception {
    long id = idOf(ALICE, "secret.txt");

    mockMvc
        .perform(get("/api/files/" + id + "/versions").with(BOB))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(post("/api/files/" + id + "/versions/1/restore").with(BOB).with(csrf()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/files/" + id + "/versions/1/restore")
                .param("mode", "COPY")
                .with(BOB)
                .with(csrf()))
        .andExpect(status().isNotFound());

    assertThat(storagePaths.home(alice).root().resolve("secret.txt"))
        .hasContent("alice's newer secret plans");
    assertThat(storagePaths.home(bob).root().resolve("secret_v1.txt")).doesNotExist();
    mockMvc
        .perform(get("/api/files/" + id + "/versions").with(ALICE))
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  @DisplayName("another account's file cannot be deleted")
  void deleteIsPrivate() throws Exception {
    mockMvc
        .perform(delete("/api/files").param("path", "secret.txt").with(BOB).with(csrf()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            delete("/api/files")
                .param("path", "../" + alice.getId() + "/secret.txt")
                .with(BOB)
                .with(csrf()))
        .andExpect(status().isBadRequest());

    assertThat(storagePaths.home(alice).root().resolve("secret.txt")).exists();
  }

  @Test
  @DisplayName("another account's files are not in search results")
  void searchIsPrivate() throws Exception {
    searchIndex.awaitIdle(Duration.ofSeconds(30));

    assertThat(searchPaths(BOB, "plans")).isEmpty();
    assertThat(searchPaths(ALICE, "plans")).containsExactly("secret.txt");
  }

  @Test
  @DisplayName("each account sees only its own history")
  void historyIsPrivate() throws Exception {
    mockMvc.perform(get("/api/history").with(BOB)).andExpect(jsonPath("$.totalItems").value(0));
    mockMvc.perform(get("/api/history").with(ALICE)).andExpect(jsonPath("$.totalItems").value(2));
  }

  @Test
  @DisplayName("each account's storage use counts its own files and versions only")
  void storageUseIsPrivate() throws Exception {
    long current = "alice's newer secret plans".length();
    long previous = "alice's secret plans".length();

    mockMvc
        .perform(get("/api/storage").with(ALICE))
        .andExpect(jsonPath("$.usedBytes").value(current + previous));
    mockMvc.perform(get("/api/storage").with(BOB)).andExpect(jsonPath("$.usedBytes").value(0));
  }

  @Test
  @DisplayName("a disabled account's share links stop opening, and open again once it is enabled")
  void disabledAccountsLinksStopOpening() throws Exception {
    String token = share(ALICE, "secret.txt");
    alice.setEnabled(false);
    users.save(alice);

    mockMvc.perform(get("/share/" + token).with(anonymous())).andExpect(status().isNotFound());

    alice.setEnabled(true);
    users.save(alice);
    mockMvc.perform(get("/share/" + token).with(anonymous())).andExpect(status().isOk());
  }

  private void upload(RequestPostProcessor as, String name, String content) throws Exception {
    upload(as, "", name, content);
  }

  private void upload(RequestPostProcessor as, String folder, String name, String content)
      throws Exception {
    mockMvc
        .perform(
            multipart("/api/files")
                .file(
                    new MockMultipartFile(
                        "files", name, "text/plain", content.getBytes(StandardCharsets.UTF_8)))
                .param("path", folder)
                .with(as)
                .with(csrf().asHeader()))
        .andExpect(status().isOk());
  }

  private String share(RequestPostProcessor as, String path) throws Exception {
    String url =
        json.readTree(
                mockMvc
                    .perform(post("/api/share").param("path", path).with(as).with(csrf()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("url")
            .asText();
    return url.substring(url.lastIndexOf('/') + 1);
  }

  private long idOf(RequestPostProcessor as, String path) throws Exception {
    return node(as, path).get("id").asLong();
  }

  // The item at path in the account's file tree, however deep.
  private JsonNode node(RequestPostProcessor as, String path) throws Exception {
    JsonNode tree =
        json.readTree(
            mockMvc
                .perform(get("/api/files").with(as))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    List<JsonNode> pending = new ArrayList<>();
    tree.forEach(pending::add);
    while (!pending.isEmpty()) {
      JsonNode node = pending.remove(0);
      if (node.get("relativePath").asText().equals(path)) {
        return node;
      }
      node.path("children").forEach(pending::add);
    }
    throw new AssertionError(path + " is not in the tree");
  }

  private List<String> searchPaths(RequestPostProcessor as, String q) throws Exception {
    JsonNode answer =
        json.readTree(
            mockMvc
                .perform(get("/api/search").param("q", q).with(as))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    List<String> paths = new ArrayList<>();
    answer.path("results").forEach(result -> paths.add(result.path("relativePath").asText()));
    return paths;
  }
}
