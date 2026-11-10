package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.LocalFileStore;
import com.javadropbox.javadropbox.service.SearchIndex;
import com.javadropbox.javadropbox.service.StoragePaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.assertj.core.groups.Tuple;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Importing files added outside the app")
@TempServingDirectory
class ImportIntegrationTests {

  private static final RequestPostProcessor ADMIN = user("root").roles("ADMIN");
  private static final RequestPostProcessor BOB = user("bob");

  @Value("${javadropbox.serving.directory}")
  private Path servingDir;

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private FileMetadataRepository metadata;
  @Autowired private StoragePaths storagePaths;
  @Autowired private LocalFileStore localStore;
  @Autowired private SearchIndex searchIndex;
  @Autowired private JdbcTemplate jdbc;

  private User bob;
  // Bob's folder on disk, where files get copied by hand.
  private Path home;

  @BeforeEach
  void setUp() {
    users.save(new User("root", "unused", User.ROLE_ADMIN));
    bob = users.save(new User("bob", "unused", User.ROLE_USER));
    home = localStore.path(storagePaths.home(bob).key());
  }

  @AfterEach
  void tearDown() throws Exception {
    TestDatabase.wipe(jdbc);
    TestStorage.empty(servingDir);
    searchIndex.reconcile();
    searchIndex.awaitIdle(Duration.ofSeconds(30));
  }

  @Test
  @DisplayName("files and folders copied in by hand get rows of the account's, dated as on disk")
  void importsWhatWasCopiedIn() throws Exception {
    upload("", "uploaded.txt", "via the app");
    Files.createDirectories(home.resolve("Photos/2024"));
    Files.writeString(home.resolve("Photos/2024/beach.jpg"), "jpeg bytes");
    Files.writeString(home.resolve("notes.txt"), "copied in");
    Instant lastWeek = Instant.now().minus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    Files.setLastModifiedTime(home.resolve("notes.txt"), FileTime.from(lastWeek));

    runImport(bob)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.files").value(2))
        .andExpect(jsonPath("$.folders").value(2))
        .andExpect(jsonPath("$.missing").value(0))
        .andExpect(jsonPath("$.skipped").value(0));

    assertThat(metadata.findAllOf(bob.getId()))
        .extracting(FileMetadata::getPath, FileMetadata::getIsDirectory, FileMetadata::getSize)
        .containsExactlyInAnyOrder(
            Tuple.tuple("uploaded.txt", false, 11L),
            Tuple.tuple("notes.txt", false, 9L),
            Tuple.tuple("Photos", true, 0L),
            Tuple.tuple("Photos/2024", true, 0L),
            Tuple.tuple("Photos/2024/beach.jpg", false, 10L));
    FileMetadata notes = metadata.findByPath(bob.getId(), "notes.txt").orElseThrow();
    assertThat(notes.getUpdatedAt()).isEqualTo(lastWeek);
    mockMvc
        .perform(get("/api/files").with(BOB))
        .andExpect(jsonPath("$[?(@.name == 'notes.txt')].id").value((int) (long) notes.getId()))
        .andExpect(jsonPath("$[?(@.name == 'notes.txt')].ownerName").value("bob"));
  }

  @Test
  @DisplayName("importing again adds nothing, and hidden files are left out")
  void importIsRepeatableAndSkipsHiddenFiles() throws Exception {
    Files.writeString(home.resolve("a.txt"), "a");
    Files.writeString(home.resolve(".DS_Store"), "finder");
    Files.createDirectories(home.resolve(".git"));
    Files.writeString(home.resolve(".git/HEAD"), "ref");

    runImport(bob).andExpect(jsonPath("$.files").value(1));
    runImport(bob)
        .andExpect(jsonPath("$.files").value(0))
        .andExpect(jsonPath("$.folders").value(0));

    assertThat(metadata.findPathsOf(bob.getId())).containsExactly("a.txt");
  }

  @Test
  @DisplayName("a file imported can be replaced like an uploaded one, its old content kept")
  void importedFileIsVersioned() throws Exception {
    Files.writeString(home.resolve("a.txt"), "by hand");
    runImport(bob);
    long id = metadata.findByPath(bob.getId(), "a.txt").orElseThrow().getId();

    upload("", "a.txt", "uploaded");

    assertThat(metadata.findByPath(bob.getId(), "a.txt").orElseThrow().getId()).isEqualTo(id);
    mockMvc
        .perform(get("/api/files/" + id + "/versions").with(BOB))
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  @DisplayName("rows whose files were removed by hand are counted, never deleted")
  void missingFilesAreOnlyCounted() throws Exception {
    upload("", "kept.txt", "x");
    upload("Docs", "gone.txt", "y");
    Files.delete(home.resolve("Docs/gone.txt"));

    runImport(bob)
        .andExpect(jsonPath("$.files").value(0))
        .andExpect(jsonPath("$.missing").value(1));

    assertThat(metadata.findPathsOf(bob.getId()))
        .containsExactlyInAnyOrder("kept.txt", "Docs", "Docs/gone.txt");
  }

  @Test
  @DisplayName("a store holding none of the account's files is refused, and nothing is imported")
  void foreignStoreIsRefused() throws Exception {
    upload("", "mine.txt", "x");
    // As if the serving directory were someone else's: what the rows describe isn't there.
    Files.delete(home.resolve("mine.txt"));
    Files.writeString(home.resolve("theirs.txt"), "not bob's");

    runImport(bob)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("wrong")));

    assertThat(metadata.findPathsOf(bob.getId())).containsExactly("mine.txt");
  }

  @Test
  @DisplayName("an empty folder for an account with rows is refused, and nothing changes")
  void emptyFolderForAnAccountWithRowsIsRefused() throws Exception {
    upload("", "a.txt", "x");
    upload("Docs", "b.txt", "y");
    TestStorage.empty(servingDir);

    runImport(bob).andExpect(status().isConflict());

    assertThat(metadata.findPathsOf(bob.getId()))
        .containsExactlyInAnyOrder("a.txt", "Docs", "Docs/b.txt");
  }

  @Test
  @DisplayName("an empty folder with no rows imports nothing")
  void emptyFolderImportsNothing() throws Exception {
    runImport(bob)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.files").value(0))
        .andExpect(jsonPath("$.folders").value(0))
        .andExpect(jsonPath("$.missing").value(0));

    assertThat(metadata.findAll()).isEmpty();
  }

  @Test
  @DisplayName("only an admin can start an import, and only for an account that exists")
  void onlyAdminsImport() throws Exception {
    Files.writeString(home.resolve("a.txt"), "a");

    mockMvc
        .perform(post("/api/admin/users/" + bob.getId() + "/import").with(BOB).with(csrf()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(post("/api/admin/users/999999/import").with(ADMIN).with(csrf()))
        .andExpect(status().isNotFound());

    assertThat(metadata.findAll()).isEmpty();
  }

  @Test
  @DisplayName("an import only touches the account it was started for")
  void importIsPerAccount() throws Exception {
    User carol = users.save(new User("carol", "unused", User.ROLE_USER));
    Path carolsHome = localStore.path(storagePaths.home(carol).key());
    Files.writeString(carolsHome.resolve("carol.txt"), "c");
    Files.writeString(home.resolve("bob.txt"), "b");

    runImport(bob).andExpect(jsonPath("$.files").value(1));

    assertThat(metadata.findPathsOf(bob.getId())).containsExactly("bob.txt");
    assertThat(metadata.findPathsOf(carol.getId())).isEmpty();
  }

  private ResultActions runImport(User account) throws Exception {
    return mockMvc.perform(
        post("/api/admin/users/" + account.getId() + "/import").with(ADMIN).with(csrf()));
  }

  private void upload(String folder, String name, String content) throws Exception {
    mockMvc
        .perform(
            multipart("/api/files")
                .file(
                    new MockMultipartFile(
                        "files", name, "text/plain", content.getBytes(StandardCharsets.UTF_8)))
                .param("path", folder)
                .with(BOB)
                .with(csrf().asHeader()))
        .andExpect(status().isOk());
  }
}
