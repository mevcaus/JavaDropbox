package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.SearchIndex;
import com.javadropbox.javadropbox.service.StoragePaths;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "owner")
@DisplayName("Search")
class SearchIntegrationTests {

  private static final Duration INDEXING = Duration.ofSeconds(30);

  @TempDir static Path servingDir;
  @TempDir static Path outsideDir;

  @DynamicPropertySource
  static void overrideServingDirectory(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private FileMetadataRepository metadata;
  @Autowired private SearchIndex searchIndex;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;

  @Autowired private StoragePaths storagePaths;

  private User owner;
  // The signed-in account's folder, where its files are.
  private Path home;

  @BeforeEach
  void setUp() {
    owner = users.save(new User("owner", "unused", "ROLE_ADMIN"));
    home = storagePaths.home(owner).root();
  }

  // Everything goes, the index with it, so each test starts from nothing.
  @AfterEach
  void tearDown() throws Exception {
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
    searchIndex.reconcile();
    searchIndex.awaitIdle(INDEXING);
  }

  @Test
  @DisplayName("finds a file by a word in its text, and marks the word in the passage it shows")
  void findsFilesByTheirText() throws Exception {
    upload("notes", "plan.txt", "Shopping list.\nThe quarterly budget is due on Friday.");
    upload("notes", "other.txt", "Nothing to see here.");

    JsonNode answer = search("budget");

    assertThat(answer.path("total").asLong()).isEqualTo(1);
    assertThat(answer.path("indexing").asBoolean()).isFalse();
    JsonNode result = answer.path("results").get(0);
    assertThat(result.path("relativePath").asText()).isEqualTo("notes/plan.txt");
    assertThat(result.path("name").asText()).isEqualTo("plan.txt");
    assertThat(result.path("isDirectory").asBoolean()).isFalse();
    assertThat(result.path("size").asLong()).isEqualTo(Files.size(home.resolve("notes/plan.txt")));
    assertThat(result.path("previewType").asText()).isEqualTo("text");
    // All of it fits, so nothing is marked as cut.
    assertThat(result.path("snippet").path("text").asText())
        .isEqualTo("Shopping list.\nThe quarterly budget is due on Friday.");
    assertThat(marked(result)).containsExactly("budget");
  }

  @Test
  @DisplayName("shows about a sentence of a long text around the match, marking where it is cut")
  void showsAPassageOfLongText() throws Exception {
    upload(
        "",
        "long.txt",
        "Filler words. ".repeat(100) + "The needle is here. " + "More filler. ".repeat(100));

    JsonNode result = search("needle").path("results").get(0);
    String snippet = result.path("snippet").path("text").asText();

    assertThat(snippet)
        .startsWith("…")
        .endsWith("…")
        .contains("The needle is here.")
        .hasSizeLessThan(200);
    assertThat(snippet.substring(1)).doesNotStartWith(" ").doesNotStartWith(".");
    assertThat(marked(result)).containsExactly("needle");
  }

  @Test
  @DisplayName("finds files and folders by part of their name, whatever the case and accents")
  void findsItemsByName() throws Exception {
    createFolder("", "Résumés");
    upload("Résumés", "Čaušević CV.txt", "Nothing in here matches.");

    assertThat(paths(search("resum"))).containsExactly("Résumés");
    assertThat(paths(search("CAUSEVIC"))).containsExactly("Résumés/Čaušević CV.txt");
    assertThat(paths(search("cv.t"))).containsExactly("Résumés/Čaušević CV.txt");
    // A match on the name alone has no passage of text to show.
    assertThat(search("resum").path("results").get(0).path("snippet").isNull()).isTrue();
  }

  @Test
  @DisplayName("reads the text of PDFs and Word documents")
  void readsPdfsAndWordDocuments() throws Exception {
    upload("", "invoice.pdf", TestDocuments.pdf("Cover page", "Invoice for gardening services"));
    upload("", "minutes.docx", TestDocuments.docx("Minutes of the annual meeting", "Apologies"));

    JsonNode pdf = search("gardening").path("results").get(0);
    assertThat(pdf.path("relativePath").asText()).isEqualTo("invoice.pdf");
    assertThat(pdf.path("previewType").asText()).isEqualTo("pdf");
    assertThat(marked(pdf)).containsExactly("gardening");

    assertThat(paths(search("annual meeting"))).containsExactly("minutes.docx");
    assertThat(paths(search("apologies"))).containsExactly("minutes.docx");
  }

  @Test
  @DisplayName("needs every word, in the name or the text, and takes the last as the start of one")
  void needsEveryWord() throws Exception {
    upload("", "fruit.txt", "Apples and pears.");
    upload("", "apples.txt", "Only one kind here.");
    upload("", "pears.txt", "Apples as well.");

    assertThat(paths(search("apples pears"))).containsExactlyInAnyOrder("fruit.txt", "pears.txt");
    assertThat(paths(search("apples pea"))).containsExactlyInAnyOrder("fruit.txt", "pears.txt");
    assertThat(paths(search("kind"))).containsExactly("apples.txt");
    assertThat(paths(search("kin"))).containsExactly("apples.txt");
    assertThat(paths(search("apples bananas"))).isEmpty();
  }

  @Test
  @DisplayName("puts matches in the name first, and words that appear together ahead of apart")
  void ranksBetterMatchesFirst() throws Exception {
    upload("", "notes.txt", "These are the minutes of the meeting, minutes and minutes of them.");
    upload("", "minutes.txt", "Nothing about it in here.");
    assertThat(paths(search("minutes"))).containsExactly("minutes.txt", "notes.txt");

    // The shorter file would rank first on its words alone; the phrase puts the other one ahead.
    upload("", "apart.txt", "The review of the annual party.");
    upload(
        "", "together.txt", "Our annual review is next week, after the long break and the move.");
    assertThat(paths(search("annual review"))).containsExactly("together.txt", "apart.txt");
  }

  @Test
  @DisplayName("searches only below the folder it is given")
  void searchesBelowTheFolder() throws Exception {
    upload("zebra", "zebra.txt", "zebra");
    upload("zebra/deeper", "notes.txt", "a zebra crossing");
    upload("other", "zebra.md", "zebra");

    assertThat(paths(search("zebra")))
        .containsExactlyInAnyOrder(
            "zebra", "zebra/zebra.txt", "zebra/deeper/notes.txt", "other/zebra.md");
    // Not the folder itself, only what is in it.
    assertThat(paths(searchIn("zebra", "zebra")))
        .containsExactlyInAnyOrder("zebra/zebra.txt", "zebra/deeper/notes.txt");
    assertThat(paths(searchIn("zebra/deeper", "zebra"))).containsExactly("zebra/deeper/notes.txt");
  }

  @Test
  @DisplayName("follows uploads over a file, restores and deletes")
  void followsChangesMadeInTheApp() throws Exception {
    upload("docs", "report.txt", "first draft");
    assertThat(paths(search("draft"))).containsExactly("docs/report.txt");

    upload("docs", "report.txt", "final copy");
    assertThat(paths(search("draft"))).isEmpty();
    assertThat(paths(search("final"))).containsExactly("docs/report.txt");

    long id = metadata.findByPath(owner.getId(), "docs/report.txt").orElseThrow().getId();
    restore(id, 1, "COPY");
    assertThat(paths(search("draft"))).containsExactly("docs/report_v1.txt");
    restore(id, 1, "OVERWRITE");
    assertThat(paths(search("draft")))
        .containsExactlyInAnyOrder("docs/report.txt", "docs/report_v1.txt");

    mockMvc
        .perform(delete("/api/files").param("path", "docs").with(csrf()))
        .andExpect(status().isOk());
    searchIndex.awaitIdle(INDEXING);
    assertThat(paths(search("draft"))).isEmpty();
    assertThat(paths(search("docs"))).isEmpty();
  }

  @Test
  @DisplayName("does not search previous versions")
  void leavesVersionsOut() throws Exception {
    upload("", "list.txt", "milk eggs");
    upload("", "list.txt", "bread");

    assertThat(paths(search("milk"))).isEmpty();
  }

  @Test
  @DisplayName("picks up files added, changed or removed outside the app when it reconciles")
  void reconcilesWithTheDisk() throws Exception {
    Files.createDirectories(home.resolve("copied"));
    Path file = home.resolve("copied/readme.md");
    Files.writeString(file, "copied in by hand");
    reconcile();
    assertThat(paths(search("hand"))).containsExactly("copied/readme.md");

    // Changed in place: a different size and a later modification time.
    Files.writeString(file, "edited in place, later on");
    Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(60)));
    reconcile();
    assertThat(paths(search("hand"))).isEmpty();
    assertThat(paths(search("edited"))).containsExactly("copied/readme.md");

    Files.delete(file);
    reconcile();
    assertThat(paths(search("edited"))).isEmpty();
    assertThat(paths(search("copied"))).containsExactly("copied");
  }

  @Test
  @DisplayName("leaves out an item that has gone since it was indexed, and drops it from the index")
  void leavesOutWhatHasGone() throws Exception {
    upload("", "gone.txt", "vanishing act");
    upload("", "kept.txt", "vanishing too");
    Files.delete(home.resolve("gone.txt"));

    assertThat(paths(search("vanishing"))).containsExactly("kept.txt");
    searchIndex.awaitIdle(INDEXING);
    assertThat(search("vanishing").path("total").asLong()).isEqualTo(1);
  }

  @Test
  @DisplayName("leaves out hidden files, the app's own folders and anything behind a symlink")
  void leavesOutWhatTheTreeHides() throws Exception {
    Files.writeString(home.resolve(".hidden.txt"), "secret");
    Files.createDirectories(home.resolve(".private"));
    Files.writeString(home.resolve(".private/notes.txt"), "secret");
    Files.writeString(outsideDir.resolve("outside.txt"), "secret");
    Files.createSymbolicLink(home.resolve("link.txt"), outsideDir.resolve("outside.txt"));
    Files.createSymbolicLink(home.resolve("linked"), outsideDir);
    reconcile();

    assertThat(paths(search("secret"))).isEmpty();
    assertThat(paths(search("link"))).isEmpty();
  }

  @Test
  @DisplayName("returns the best matches up to the limit, and how many there were in all")
  void limitsResults() throws Exception {
    for (int i = 1; i <= 5; i++) {
      upload("", "file" + i + ".txt", "common words");
    }

    JsonNode answer =
        json.readTree(
            mockMvc
                .perform(get("/api/search").param("q", "common").param("limit", "2"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(answer.path("results")).hasSize(2);
    assertThat(answer.path("total").asLong()).isEqualTo(5);
  }

  @ParameterizedTest(name = "q=\"{0}\" is refused")
  @ValueSource(strings = {"", "   "})
  void emptySearchesAreRefused(String q) throws Exception {
    mockMvc.perform(get("/api/search").param("q", q)).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("refuses overlong searches and limits out of range")
  void refusesOutOfRange() throws Exception {
    mockMvc
        .perform(get("/api/search").param("q", "a".repeat(201)))
        .andExpect(status().isBadRequest());
    for (String limit : new String[] {"0", "201"}) {
      mockMvc
          .perform(get("/api/search").param("q", "a").param("limit", limit))
          .andExpect(status().isBadRequest());
    }
  }

  @ParameterizedTest(name = "path=\"{0}\" is refused")
  @ValueSource(strings = {"..", "../outside", ".versions", ".javadropbox", "a/../../etc"})
  void foldersOutsideTheServingDirectoryAreRefused(String path) throws Exception {
    mockMvc
        .perform(get("/api/search").param("q", "secret").param("path", path))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("a folder that does not exist is a 404")
  void missingFolderIsNotFound() throws Exception {
    mockMvc
        .perform(get("/api/search").param("q", "anything").param("path", "nowhere"))
        .andExpect(status().isNotFound());
  }

  @Test
  @WithAnonymousUser
  @DisplayName("searching needs a session")
  void searchRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/search").param("q", "anything")).andExpect(status().isUnauthorized());
  }

  private void upload(String folder, String name, String text) throws Exception {
    upload(folder, name, text.getBytes(StandardCharsets.UTF_8));
  }

  private void upload(String folder, String name, byte[] content) throws Exception {
    mockMvc
        .perform(
            multipart("/api/files")
                .file(new MockMultipartFile("files", name, "application/octet-stream", content))
                .param("path", folder)
                .with(csrf().asHeader()))
        .andExpect(status().isOk());
    searchIndex.awaitIdle(INDEXING);
  }

  private void createFolder(String parent, String name) throws Exception {
    mockMvc
        .perform(post("/api/folders").param("path", parent).param("name", name).with(csrf()))
        .andExpect(status().isOk());
    searchIndex.awaitIdle(INDEXING);
  }

  private void restore(long id, int version, String mode) throws Exception {
    mockMvc
        .perform(
            post("/api/files/" + id + "/versions/" + version + "/restore")
                .param("mode", mode)
                .with(csrf()))
        .andExpect(status().isOk());
    searchIndex.awaitIdle(INDEXING);
  }

  private void reconcile() throws InterruptedException {
    searchIndex.reconcile();
    searchIndex.awaitIdle(INDEXING);
  }

  private JsonNode search(String q) throws Exception {
    return answer(mockMvc.perform(get("/api/search").param("q", q)));
  }

  private JsonNode searchIn(String folder, String q) throws Exception {
    return answer(mockMvc.perform(get("/api/search").param("q", q).param("path", folder)));
  }

  private JsonNode answer(ResultActions request) throws Exception {
    return json.readTree(
        request
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8));
  }

  private static List<String> paths(JsonNode answer) {
    List<String> paths = new ArrayList<>();
    answer.path("results").forEach(result -> paths.add(result.path("relativePath").asText()));
    return paths;
  }

  // The parts of the snippet's text that its highlights mark.
  private static List<String> marked(JsonNode result) {
    String text = result.path("snippet").path("text").asText();
    List<String> marked = new ArrayList<>();
    result
        .path("snippet")
        .path("highlights")
        .forEach(
            highlight ->
                marked.add(
                    text.substring(
                        highlight.path("start").asInt(), highlight.path("end").asInt())));
    return marked;
  }
}
