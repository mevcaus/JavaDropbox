package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.FileVersion;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.FileVersionRepository;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.DemoService;
import com.javadropbox.javadropbox.service.SearchIndex;
import com.javadropbox.javadropbox.service.StoragePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@DisplayName("Public demo (demo profile)")
class DemoIntegrationTests {

  @TempDir static Path servingDir;

  @DynamicPropertySource
  static void overrideServingDirectory(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private DemoService demo;
  @Autowired private FileMetadataRepository metadata;
  @Autowired private FileVersionRepository versions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;
  @Autowired private SearchIndex searchIndex;
  @Autowired private UserRepository users;
  @Autowired private StoragePaths storagePaths;

  // Each test starts from a fresh demo, as on a first start: no account, no files, no reset yet.
  @BeforeEach
  void setUp() throws Exception {
    clear();
    demo.run(new DefaultApplicationArguments());
  }

  // Leaves nothing behind for the test classes that run next on the same database.
  @AfterEach
  void tearDown() throws IOException {
    clear();
  }

  @Test
  @DisplayName("the demo account signs in without any setup")
  void demoAccountSignsIn() throws Exception {
    mockMvc
        .perform(
            post("/login").param("username", "demo").param("password", "javadropbox").with(csrf()))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("GET /api/demo tells anyone the account and the limits")
  void infoIsPublic() throws Exception {
    mockMvc
        .perform(get("/api/demo"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("demo"))
        .andExpect(jsonPath("$.password").value("javadropbox"))
        .andExpect(jsonPath("$.maxUploadBytes").value(5 * 1024 * 1024))
        .andExpect(jsonPath("$.storageLimitBytes").value(50 * 1024 * 1024))
        .andExpect(jsonPath("$.maxShareMinutes").value(15));

    Instant nextReset =
        Instant.parse(
            json.readTree(
                    mockMvc
                        .perform(get("/api/demo"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("nextReset")
                .asText());
    assertThat(nextReset).isBetween(Instant.now(), Instant.now().plus(Duration.ofDays(1)));
  }

  @Test
  @DisplayName("the sample files are stored, and the notes have two previous versions")
  void samplesAreStored() {
    assertThat(metadata.findAll())
        .extracting(FileMetadata::getPath)
        .contains(
            "Welcome.md",
            "Documents/JavaDropbox overview.pdf",
            "Pictures/javadropbox-logo.png",
            "Code/Greeter.java",
            "Notes/todo.txt");
    assertThat(home().resolve("Notes/todo.txt")).content().contains("Order coffee");

    // Only the notes were stored more than once.
    assertThat(versions.findAll())
        .extracting(FileVersion::getVersion)
        .containsExactlyInAnyOrder(1, 2);
  }

  @Test
  @DisplayName("a reset that is due deletes everything and puts the samples back")
  void resetPutsTheSamplesBack() throws Exception {
    mockMvc
        .perform(
            multipart("/api/files")
                .file(
                    new MockMultipartFile(
                        "files", "mine.txt", "text/plain", "pineapple".getBytes()))
                .param("path", "")
                .with(user("demo"))
                .with(csrf().asHeader()))
        .andExpect(status().isOk());
    Files.delete(home().resolve("Welcome.md"));

    // As if the last reset were two days ago.
    Files.setLastModifiedTime(
        servingDir.resolve(".javadropbox/demo-reset"),
        FileTime.from(Instant.now().minus(Duration.ofDays(2))));
    demo.run(new DefaultApplicationArguments());

    assertThat(home().resolve("mine.txt")).doesNotExist();
    assertThat(metadata.findAll()).extracting(FileMetadata::getPath).doesNotContain("mine.txt");
    assertThat(home().resolve("Welcome.md")).exists();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_history", Long.class)).isEqualTo(7);

    // Search forgets what was deleted and finds the samples, the PDF's text among them.
    searchIndex.awaitIdle(Duration.ofSeconds(30));
    assertThat(searchPaths("pineapple")).isEmpty();
    assertThat(searchPaths("agenda")).containsExactly("Notes/todo.txt");
    assertThat(searchPaths("interrupted")).containsExactly("Documents/JavaDropbox overview.pdf");
  }

  @Test
  @DisplayName("a reset that is not due yet changes nothing")
  void resetWaitsUntilDue() throws Exception {
    Files.delete(home().resolve("Welcome.md"));

    demo.run(new DefaultApplicationArguments());

    assertThat(home().resolve("Welcome.md")).doesNotExist();
  }

  @Test
  @DisplayName("share links last at most 15 minutes, which is also the default")
  void shareLinksAreShort() throws Exception {
    mockMvc
        .perform(
            post("/api/share")
                .param("path", "Welcome.md")
                .param("expirationMinutes", "60")
                .with(user("demo"))
                .with(csrf()))
        .andExpect(status().isBadRequest());

    String body =
        mockMvc
            .perform(post("/api/share").param("path", "Welcome.md").with(user("demo")).with(csrf()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Instant expiresAt = Instant.parse(json.readTree(body).get("expiresAt").asText());
    assertThat(expiresAt).isBefore(Instant.now().plus(Duration.ofMinutes(15).plusSeconds(5)));
  }

  @Test
  @DisplayName("the demo account is no admin, so visitors cannot invite anyone or see metrics")
  void demoAccountIsNoAdmin() throws Exception {
    mockMvc
        .perform(
            post("/login").param("username", "demo").param("password", "javadropbox").with(csrf()))
        .andExpect(status().isOk());
    assertThat(users.findByUsername("demo").orElseThrow().isAdmin()).isFalse();

    mockMvc
        .perform(
            post("/api/admin/invites")
                .param("username", "freeloader")
                .with(user("demo"))
                .with(csrf()))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/actuator/metrics").with(user("demo"))).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("a demo account made an admin, as upgrading makes the first account, is undone")
  void demoAccountIsMadeAnOrdinaryAccountAgain() throws Exception {
    User account = users.findByUsername("demo").orElseThrow();
    account.setRole(User.ROLE_ADMIN);
    account.setEnabled(false);
    users.save(account);

    demo.run(new DefaultApplicationArguments());

    User after = users.findByUsername("demo").orElseThrow();
    assertThat(after.isAdmin()).isFalse();
    assertThat(after.isEnabled()).isTrue();
    assertThat(after.getSessionVersion()).isGreaterThan(account.getSessionVersion());
  }

  // The demo account's folder.
  private Path home() {
    return storagePaths.home(users.findByUsername("demo").orElseThrow()).root();
  }

  private List<String> searchPaths(String q) throws Exception {
    String body =
        mockMvc
            .perform(get("/api/search").param("q", q).with(user("demo")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    List<String> paths = new ArrayList<>();
    json.readTree(body).path("results").forEach(r -> paths.add(r.path("relativePath").asText()));
    return paths;
  }

  private void clear() throws IOException {
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
}
