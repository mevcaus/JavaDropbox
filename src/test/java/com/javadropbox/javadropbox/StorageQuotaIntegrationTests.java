package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.StoragePaths;
import com.javadropbox.javadropbox.service.StorageQuota;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "javadropbox.storage.max-total-size=1KB")
@AutoConfigureMockMvc
@WithMockUser(username = "owner")
@DisplayName("Storage caps: an account's quota and the server's")
class StorageQuotaIntegrationTests {

  @TempDir static Path servingDir;

  @DynamicPropertySource
  static void overrideServingDirectory(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private FileMetadataRepository metadata;
  @Autowired private StorageQuota quota;
  @Autowired private JdbcTemplate jdbc;

  @Autowired private StoragePaths storagePaths;

  private User owner;
  // The signed-in account's folder, where its files are.
  private Path home;

  @BeforeEach
  void setUp() {
    owner = users.save(new User("owner", "unused", "ROLE_ADMIN"));
    home = storagePaths.home(owner).root();
  }

  @AfterEach
  void tearDown() throws IOException {
    TestDatabase.wipe(jdbc);
    try (Stream<Path> entries = Files.list(servingDir)) {
      for (Path entry : entries.toList()) {
        try (Stream<Path> walk = Files.walk(entry)) {
          for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
            Files.delete(p);
          }
        }
      }
    }
  }

  @Test
  @DisplayName("an upload that would go over the cap is a 507 and leaves nothing behind")
  void uploadOverTheCapIsRefused() throws Exception {
    upload("a.txt", 600).andExpect(status().isOk());

    upload("b.txt", 600)
        .andExpect(status().isInsufficientStorage())
        .andExpect(jsonPath("$.message").value(Matchers.containsString("at most 1 KB")));

    assertThat(home.resolve("b.txt")).doesNotExist();
    assertThat(metadata.findByPath(owner.getId(), "b.txt")).isEmpty();
    assertThat(quota.usedBytes()).isEqualTo(600);
  }

  @Test
  @DisplayName("previous versions count toward the cap")
  void versionsCount() throws Exception {
    upload("a.txt", 400).andExpect(status().isOk());
    upload("a.txt", 400).andExpect(status().isOk());

    upload("a.txt", 400).andExpect(status().isInsufficientStorage());

    assertThat(quota.usedBytes()).isEqualTo(800);
    assertThat(home.resolve("a.txt")).hasSize(400);
  }

  @Test
  @DisplayName("restoring a version as a copy is refused when the copy would not fit")
  void restoreOverTheCapIsRefused() throws Exception {
    upload("a.txt", 400).andExpect(status().isOk());
    upload("a.txt", 300).andExpect(status().isOk());
    long id = metadata.findByPath(owner.getId(), "a.txt").orElseThrow().getId();

    mockMvc
        .perform(
            post("/api/files/" + id + "/versions/1/restore").param("mode", "COPY").with(csrf()))
        .andExpect(status().isInsufficientStorage());

    assertThat(home.resolve("a_v1.txt")).doesNotExist();
    assertThat(quota.usedBytes()).isEqualTo(700);
  }

  // --- an account's quota ---------------------------------------------------------------

  @Test
  @DisplayName("an upload over the account's quota is a 507 naming it, and leaves nothing behind")
  void uploadOverTheQuotaIsRefused() throws Exception {
    setQuota(owner, 500);
    upload("a.txt", 300).andExpect(status().isOk());

    upload("b.txt", 300)
        .andExpect(status().isInsufficientStorage())
        .andExpect(
            jsonPath("$.message")
                .value(Matchers.containsString("your account can hold at most 500 bytes")));

    assertThat(home.resolve("b.txt")).doesNotExist();
    assertThat(metadata.findByPath(owner.getId(), "b.txt")).isEmpty();
    try (Stream<Path> left = Files.list(home)) {
      assertThat(left.map(p -> p.getFileName().toString())).containsExactly("a.txt");
    }
    mockMvc.perform(get("/api/storage")).andExpect(jsonPath("$.usedBytes").value(300));
  }

  @Test
  @DisplayName("previous versions count toward the account's quota")
  void versionsCountTowardTheQuota() throws Exception {
    setQuota(owner, 700);
    upload("a.txt", 300).andExpect(status().isOk());
    upload("a.txt", 300).andExpect(status().isOk());

    upload("a.txt", 300).andExpect(status().isInsufficientStorage());

    mockMvc
        .perform(get("/api/storage"))
        .andExpect(jsonPath("$.usedBytes").value(600))
        .andExpect(jsonPath("$.quotaBytes").value(700));
  }

  @Test
  @DisplayName("restoring a version is refused when it would take the account over its quota")
  void restoreOverTheQuotaIsRefused() throws Exception {
    upload("a.txt", 300).andExpect(status().isOk());
    upload("a.txt", 200).andExpect(status().isOk());
    setQuota(owner, 600);
    long id = metadata.findByPath(owner.getId(), "a.txt").orElseThrow().getId();

    mockMvc
        .perform(
            post("/api/files/" + id + "/versions/1/restore").param("mode", "COPY").with(csrf()))
        .andExpect(status().isInsufficientStorage());

    assertThat(home.resolve("a_v1.txt")).doesNotExist();
  }

  @Test
  @DisplayName("another account's files do not count toward a quota, but do toward the server's")
  void otherAccountsCountOnlyTowardTheServersCap() throws Exception {
    users.save(new User("other", "unused", User.ROLE_USER));
    setQuota(owner, 500);
    upload("mine.txt", 400).andExpect(status().isOk());

    // 400 of the other account's own: within the server's 1 KB, whatever the owner's quota.
    mockMvc
        .perform(
            multipart("/api/files")
                .file(new MockMultipartFile("files", "theirs.txt", "text/plain", new byte[400]))
                .param("path", "")
                .with(user("other"))
                .with(csrf().asHeader()))
        .andExpect(status().isOk());

    // 450 of the owner's own fit in 500, though the server holds 850.
    upload("more.txt", 50).andExpect(status().isOk());

    // Within a larger quota, but the server's 1 KB is full.
    setQuota(owner, 1000);
    upload("too-much.txt", 300)
        .andExpect(status().isInsufficientStorage())
        .andExpect(jsonPath("$.message").value(Matchers.containsString("this server holds")));
  }

  @Test
  @DisplayName("the app's own state, such as the search index, does not count toward the cap")
  void internalStateDoesNotCount() throws Exception {
    Path index = Files.createDirectories(servingDir.resolve(".javadropbox/search-index"));
    Files.write(index.resolve("_0.cfs"), new byte[2000]);

    upload("a.txt", 600).andExpect(status().isOk());

    assertThat(quota.usedBytes()).isEqualTo(600);
  }

  private void setQuota(User account, long bytes) {
    account.setQuotaBytes(bytes);
    users.save(account);
  }

  private ResultActions upload(String name, int size) throws Exception {
    MockMultipartFile file = new MockMultipartFile("files", name, "text/plain", new byte[size]);
    return mockMvc.perform(
        multipart("/api/files").file(file).param("path", "").with(csrf().asHeader()));
  }
}
