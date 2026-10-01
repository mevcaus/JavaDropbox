package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.util.FileSystemUtils;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "testadmin")
@DisplayName("Share Link Integration Tests")
class ShareLinkIntegrationTests {

  @TempDir static Path servingDir;

  @DynamicPropertySource
  static void overrideServingDirectory(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
  }

  @Autowired private MockMvc mockMvc;

  @Autowired private UserRepository userRepository;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private ObjectMapper json;

  @BeforeEach
  void setUp() throws IOException {
    if (userRepository.count() == 0) {
      userRepository.save(new User("testadmin", "unused", "ROLE_ADMIN"));
    }
    Files.writeString(servingDir.resolve("shared.txt"), "share me");
  }

  @AfterEach
  void tearDown() throws IOException {
    TestDatabase.wipe(jdbc);
    try (var entries = Files.list(servingDir)) {
      for (Path entry : entries.toList()) {
        FileSystemUtils.deleteRecursively(entry);
      }
    }
  }

  @Test
  @DisplayName("Unauthenticated user cannot create a share link")
  void unauthenticatedCannotCreateShareLink() throws Exception {
    mockMvc
        .perform(post("/api/share").param("path", "shared.txt").with(csrf()).with(anonymous()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Authenticated user can create a share link for an existing file")
  void authenticatedUserCanCreateShareLink() throws Exception {
    mockMvc
        .perform(post("/api/share").param("path", "shared.txt").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value(containsString("/share/")))
        .andExpect(jsonPath("$.expiresAt").exists());
  }

  @Test
  @DisplayName("Creating a share link for a nonexistent path returns 404")
  void shareLinkForMissingPathReturns404() throws Exception {
    mockMvc
        .perform(post("/api/share").param("path", "nope.txt").with(csrf()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Out-of-range expiration is rejected")
  void outOfRangeExpirationRejected() throws Exception {
    mockMvc
        .perform(
            post("/api/share")
                .param("path", "shared.txt")
                .param("expirationMinutes", "0")
                .with(csrf()))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/share")
                .param("path", "shared.txt")
                .param("expirationMinutes", "999999")
                .with(csrf()))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("A valid share link downloads the file without authentication")
  void validLinkDownloadsWithoutAuth() throws Exception {
    String token = share("shared.txt");

    download(token)
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", containsString("shared.txt")))
        .andExpect(content().string("share me"));
  }

  @Test
  @DisplayName("An expired link is rejected")
  void expiredLinkRejected() throws Exception {
    String token = share("shared.txt");
    jdbc.update("UPDATE share_links SET expires_at = created_at - INTERVAL '1' MINUTE");

    download(token).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("A garbage token is rejected")
  void garbageTokenRejected() throws Exception {
    download("not-a-real-token").andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("The link carries only a random token: no path, and the token is not stored")
  void linkRevealsNothingAboutThePath() throws Exception {
    Files.createDirectories(servingDir.resolve("docs/private"));
    Files.writeString(servingDir.resolve("docs/private/report.pdf"), "secret");

    String token = share("docs/private/report.pdf");

    // 32 random bytes in base64url: nothing in it to decode.
    assertThat(token).matches("[A-Za-z0-9_-]{43}");
    assertThat(jdbc.queryForList("SELECT token_hash FROM share_links", String.class))
        .singleElement()
        .isNotEqualTo(token);
  }

  @Test
  @DisplayName("A link stops working when its file is deleted, even if the path is reused")
  void linkDiesWithItsFile() throws Exception {
    upload("finance", "report.pdf", "harmless draft");
    String token = share("finance/report.pdf");

    mockMvc
        .perform(delete("/api/files").param("path", "finance/report.pdf").with(csrf()))
        .andExpect(status().isOk());
    upload("finance", "report.pdf", "sensitive final numbers");

    download(token).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("A link dies when its file is removed outside the app and the path is reused")
  void linkDiesWhenItsFileIsReplacedAfterRemovalOnDisk() throws Exception {
    String token = share("shared.txt");

    Files.delete(servingDir.resolve("shared.txt"));
    upload("", "shared.txt", "someone else's file");

    download(token).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("A folder link stops working when a file takes the folder's place on disk")
  void linkDiesWhenTheItemChangesType() throws Exception {
    Files.createDirectories(servingDir.resolve("photos"));
    String token = share("photos");

    Files.delete(servingDir.resolve("photos"));
    Files.writeString(servingDir.resolve("photos"), "not a folder");

    download(token).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("A link keeps serving its file after a new version is uploaded over it")
  void linkFollowsNewVersions() throws Exception {
    upload("", "notes.txt", "first");
    String token = share("notes.txt");

    upload("", "notes.txt", "second");

    download(token).andExpect(status().isOk()).andExpect(content().string("second"));
  }

  @Test
  @DisplayName("Sharing a file copied in by hand starts tracking it")
  void sharingAnUntrackedFileTracksIt() throws Exception {
    share("shared.txt");

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM file_metadata WHERE path = 'shared.txt'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("A path's active links are listed without their tokens")
  void listsActiveLinks() throws Exception {
    Files.writeString(servingDir.resolve("other.txt"), "other");
    share("shared.txt");
    share("shared.txt");
    share("other.txt");
    String expired = share("shared.txt");
    jdbc.update(
        "UPDATE share_links SET expires_at = created_at - INTERVAL '1' MINUTE"
            + " WHERE id = (SELECT max(id) FROM share_links)");

    String body =
        mockMvc
            .perform(get("/api/share").param("path", "shared.txt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").isNumber())
            .andExpect(jsonPath("$[0].createdAt").exists())
            .andExpect(jsonPath("$[0].expiresAt").exists())
            .andExpect(jsonPath("$[0].createdBy").value("testadmin"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(expired).doesNotContain("/share/");
  }

  @Test
  @DisplayName("A path that was never shared has no links")
  void listsNothingForAnUnsharedPath() throws Exception {
    mockMvc
        .perform(get("/api/share").param("path", "shared.txt"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @DisplayName("A revoked link stops working and is no longer listed")
  void revokedLinkStopsWorking() throws Exception {
    String revoked = share("shared.txt");
    long revokedId = jdbc.queryForObject("SELECT id FROM share_links", Long.class);
    String kept = share("shared.txt");

    mockMvc.perform(delete("/api/share/" + revokedId).with(csrf())).andExpect(status().isOk());

    download(revoked).andExpect(status().isNotFound());
    download(kept).andExpect(status().isOk());
    mockMvc
        .perform(get("/api/share").param("path", "shared.txt"))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(not((int) revokedId)));
  }

  @Test
  @DisplayName("Revoking a link that does not exist returns 404")
  void revokingAnUnknownLinkReturns404() throws Exception {
    mockMvc
        .perform(delete("/api/share/12345").with(csrf()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").exists());
  }

  @Test
  @DisplayName("Listing and revoking links require signing in")
  void listingAndRevokingRequireAuth() throws Exception {
    share("shared.txt");
    long id = jdbc.queryForObject("SELECT id FROM share_links", Long.class);

    mockMvc
        .perform(get("/api/share").param("path", "shared.txt").with(anonymous()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(delete("/api/share/" + id).with(csrf()).with(anonymous()))
        .andExpect(status().isUnauthorized());
  }

  // The token at the end of the share URL.
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

  private ResultActions download(String token) throws Exception {
    return mockMvc.perform(get("/share/" + token).with(anonymous()));
  }

  private void upload(String folder, String name, String content) throws Exception {
    MockMultipartFile file =
        new MockMultipartFile(
            "files", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    mockMvc
        .perform(multipart("/api/files").file(file).param("path", folder).with(csrf().asHeader()))
        .andExpect(status().isOk());
  }
}
