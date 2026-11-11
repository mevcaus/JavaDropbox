package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.javadropbox.javadropbox.service.LocalFileStore;
import com.javadropbox.javadropbox.service.StoragePaths;
import com.javadropbox.javadropbox.service.StorageQuota;
import com.javadropbox.javadropbox.service.StorageRecount;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "owner")
@DisplayName("An account's storage quota")
@TempServingDirectory
class StorageQuotaIntegrationTests {

  @Value("${javadropbox.serving.directory}")
  private Path servingDir;

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private FileMetadataRepository metadata;
  @Autowired private StorageQuota quota;
  @Autowired private StorageRecount recount;
  @Autowired private JdbcTemplate jdbc;

  @Autowired private StoragePaths storagePaths;
  @MockitoSpyBean private LocalFileStore localStore;

  private User owner;
  // The signed-in account's folder, where its files are.
  private Path home;

  @BeforeEach
  void setUp() {
    owner = users.save(new User("owner", "unused", "ROLE_ADMIN"));
    home = localStore.path(storagePaths.home(owner).key());
  }

  @AfterEach
  void tearDown() throws IOException {
    TestDatabase.assertUsageMatchesRows(jdbc);
    TestDatabase.wipe(jdbc);
    TestStorage.empty(servingDir);
  }

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
  @DisplayName("checking an upload against the quota, and the Users page, never walk the store")
  void usageDoesNotWalkTheStore() throws Exception {
    owner.setQuotaBytes(1000L);
    users.save(owner);
    String folder = storagePaths.home(owner).key();
    clearInvocations(localStore);

    mockMvc
        .perform(
            multipart("/api/files")
                .file(new MockMultipartFile("files", "a.txt", "text/plain", "hello".getBytes()))
                .param("path", "")
                .with(csrf().asHeader()))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/storage")).andExpect(jsonPath("$.usedBytes").value(5));
    mockMvc
        .perform(get("/api/admin/users").with(user("owner").roles("ADMIN")))
        .andExpect(jsonPath("$[0].usedBytes").value(5));

    // What the quota used to do: walk the account's whole folder. (Search reads the uploaded file
    // on its own thread, which is its business.)
    verify(localStore, never()).walk(eq(folder), any());
    verify(localStore, never()).list(any());
  }

  @Test
  @DisplayName(
      "a replace refused over the quota leaves the file, its versions and the total as they were")
  void refusedReplaceChangesNothing() throws Exception {
    upload("a.txt", 40, (byte) 'a').andExpect(status().isOk());
    upload("a.txt", 40, (byte) 'b').andExpect(status().isOk());
    owner.setQuotaBytes(100L);
    users.save(owner);

    upload("a.txt", 30, (byte) 'c').andExpect(status().isInsufficientStorage());

    assertThat(Files.readAllBytes(home.resolve("a.txt"))).containsOnly((byte) 'b').hasSize(40);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM file_versions", Long.class)).isOne();
    try (Stream<Path> stored = Files.walk(servingDir.resolve(".versions"))) {
      assertThat(stored.filter(Files::isRegularFile)).hasSize(1);
    }
    assertThat(quota.usage(owner).usedBytes()).isEqualTo(80);
  }

  @Test
  @DisplayName("a total that drifted from the rows is set right by the recount at startup")
  void recountSetsDriftRight() throws Exception {
    upload("a.txt", 40).andExpect(status().isOk());
    jdbc.update("UPDATE users SET used_bytes = 7 WHERE id = ?", owner.getId());

    recount.recount();

    assertThat(quota.usage(owner).usedBytes()).isEqualTo(40);
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
  @DisplayName("restoring a version over the file is refused when that would go over the quota")
  void restoreInPlaceOverTheQuotaIsRefused() throws Exception {
    upload("a.txt", 300, (byte) 1).andExpect(status().isOk());
    upload("a.txt", 200, (byte) 2).andExpect(status().isOk());
    // 500 stored now. Restored, version 1 would be the file and the current one a version: 800.
    setQuota(owner, 600);
    long id = metadata.findByPath(owner.getId(), "a.txt").orElseThrow().getId();

    mockMvc
        .perform(post("/api/files/" + id + "/versions/1/restore").with(csrf()))
        .andExpect(status().isInsufficientStorage())
        .andExpect(
            jsonPath("$.message")
                .value(Matchers.containsString("your account can hold at most 600 bytes")));

    assertThat(Files.readAllBytes(home.resolve("a.txt"))).hasSize(200).containsOnly(2);
    mockMvc
        .perform(get("/api/files/" + id + "/versions"))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].version").value(1));
    try (Stream<Path> left = Files.list(home)) {
      assertThat(left.map(p -> p.getFileName().toString())).containsExactly("a.txt");
    }
    mockMvc.perform(get("/api/storage")).andExpect(jsonPath("$.usedBytes").value(500));
  }

  @Test
  @DisplayName("another account's files do not count toward a quota")
  void otherAccountsDoNotCount() throws Exception {
    users.save(new User("other", "unused", User.ROLE_USER));
    setQuota(owner, 500);
    upload("mine.txt", 400).andExpect(status().isOk());

    // 400 of the other account's own, whatever the owner's quota.
    mockMvc
        .perform(
            multipart("/api/files")
                .file(new MockMultipartFile("files", "theirs.txt", "text/plain", new byte[400]))
                .param("path", "")
                .with(user("other"))
                .with(csrf().asHeader()))
        .andExpect(status().isOk());

    // 450 of the owner's own fit in 500, though the serving directory holds 850.
    upload("more.txt", 50).andExpect(status().isOk());
    upload("too-much.txt", 100).andExpect(status().isInsufficientStorage());
    assertThat(quota.usage(owner).usedBytes()).isEqualTo(450);
  }

  @Test
  @DisplayName("the app's own state, such as the search index, does not count toward a quota")
  void internalStateDoesNotCount() throws Exception {
    setQuota(owner, 1000);
    Path index = Files.createDirectories(servingDir.resolve(".javadropbox/search-index"));
    Files.write(index.resolve("_0.cfs"), new byte[2000]);

    upload("a.txt", 600).andExpect(status().isOk());

    assertThat(quota.usage(owner).usedBytes()).isEqualTo(600);
  }

  private void setQuota(User account, long bytes) {
    account.setQuotaBytes(bytes);
    users.save(account);
  }

  private ResultActions upload(String name, int size) throws Exception {
    return upload(name, size, (byte) 0);
  }

  // A file of size bytes, every one of them fill, so that one content can be told from another.
  private ResultActions upload(String name, int size, byte fill) throws Exception {
    byte[] content = new byte[size];
    Arrays.fill(content, fill);
    MockMultipartFile file = new MockMultipartFile("files", name, "text/plain", content);
    return mockMvc.perform(
        multipart("/api/files").file(file).param("path", "").with(csrf().asHeader()));
  }
}
