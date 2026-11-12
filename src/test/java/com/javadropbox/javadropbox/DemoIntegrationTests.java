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
import com.javadropbox.javadropbox.service.AccountSessions;
import com.javadropbox.javadropbox.service.DemoService;
import com.javadropbox.javadropbox.service.FileService;
import com.javadropbox.javadropbox.service.LocalFileStore;
import com.javadropbox.javadropbox.service.SearchIndex;
import com.javadropbox.javadropbox.service.StoragePaths;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@DisplayName("Public demo (demo profile)")
@TempServingDirectory
class DemoIntegrationTests {

  @Value("${javadropbox.serving.directory}")
  private Path servingDir;

  @Value("${javadropbox.demo.reset-at}")
  private LocalTime resetAt;

  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private FileService fileService;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private AccountSessions accountSessions;

  @Autowired private MockMvc mockMvc;
  @Autowired private DemoService demo;
  @Autowired private FileMetadataRepository metadata;
  @Autowired private FileVersionRepository versions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;
  @Autowired private SearchIndex searchIndex;
  @Autowired private UserRepository users;
  @Autowired private StoragePaths storagePaths;
  @Autowired private LocalFileStore localStore;
  @Autowired private JdbcIndexedSessionRepository sessionRepository;

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

    lastResetDaysAgo(2);
    demo.run(new DefaultApplicationArguments());

    assertThat(home().resolve("mine.txt")).doesNotExist();
    assertThat(metadata.findAll()).extracting(FileMetadata::getPath).doesNotContain("mine.txt");
    assertThat(home().resolve("Welcome.md")).exists();
    // Seven uploads, and the four folders they made on the way.
    assertThat(
            jdbc.queryForList(
                "SELECT change_type, count(*) FROM file_history GROUP BY change_type ORDER BY 1"))
        .containsExactly(
            Map.of("change_type", "CREATE_FOLDER", "count", 4L),
            Map.of("change_type", "UPLOAD", "count", 7L));

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
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("path", "Welcome.md")
                .param("expirationMinutes", "60")
                .with(user("demo"))
                .with(csrf()))
        .andExpect(status().isBadRequest());

    String body =
        mockMvc
            .perform(
                post("/api/share")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("path", "Welcome.md")
                    .with(user("demo"))
                    .with(csrf()))
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
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "freeloader")
                .with(user("demo"))
                .with(csrf()))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/actuator/metrics").with(user("demo"))).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("the demo account has a 50 MB quota, put back at start if it was changed")
  void demoAccountHasAQuota() throws Exception {
    long quota = 50L * 1024 * 1024;
    assertThat(users.findByUsername("demo").orElseThrow().getQuotaBytes()).isEqualTo(quota);

    User account = users.findByUsername("demo").orElseThrow();
    account.setQuotaBytes(null);
    users.save(account);
    demo.run(new DefaultApplicationArguments());

    assertThat(users.findByUsername("demo").orElseThrow().getQuotaBytes()).isEqualTo(quota);
    // Reported to the demo as its own use against that quota.
    mockMvc
        .perform(get("/api/storage").cookie(signIn()))
        .andExpect(jsonPath("$.quotaBytes").value(50 * 1024 * 1024));
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

  @Test
  @DisplayName("a disabled demo account is enabled again, without bringing its old sessions back")
  void disabledDemoAccountIsEnabled() throws Exception {
    Cookie session = signIn();
    User account = users.findByUsername("demo").orElseThrow();
    account.setEnabled(false);
    users.save(account);

    demo.run(new DefaultApplicationArguments());

    User after = users.findByUsername("demo").orElseThrow();
    assertThat(after.isEnabled()).isTrue();
    assertThat(after.isAdmin()).isFalse();
    assertThat(after.getSessionVersion()).isGreaterThan(account.getSessionVersion());
    assertThat(TestDatabase.sessionsOf(jdbc, "demo")).isZero();
    mockMvc.perform(get("/api/files").cookie(session)).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/files").cookie(signIn())).andExpect(status().isOk());
  }

  @Test
  @DisplayName("a demo account that is an admin is made a user, and its admin sessions end")
  void adminDemoAccountIsMadeAUser() throws Exception {
    User account = users.findByUsername("demo").orElseThrow();
    account.setRole(User.ROLE_ADMIN);
    users.save(account);
    Cookie session = signIn();
    mockMvc.perform(get("/api/admin/users").cookie(session)).andExpect(status().isOk());

    demo.run(new DefaultApplicationArguments());

    User after = users.findByUsername("demo").orElseThrow();
    assertThat(after.isAdmin()).isFalse();
    assertThat(after.isEnabled()).isTrue();
    assertThat(after.getSessionVersion()).isGreaterThan(account.getSessionVersion());
    assertThat(TestDatabase.sessionsOf(jdbc, "demo")).isZero();
    mockMvc.perform(get("/api/admin/users").cookie(session)).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/admin/users").cookie(signIn())).andExpect(status().isForbidden());
  }

  // A schedule would take a database connection every minute and keep one open into every
  // suspend; sign-ins delete expired sessions instead.
  @Test
  @DisplayName("Spring Session's cleanup schedule is off")
  void sessionCleanupScheduleIsOff() {
    assertThat(ReflectionTestUtils.getField(sessionRepository, "taskScheduler")).isNull();
  }

  // The session cookie of the demo account, signed in through the login form like a browser's.
  private Cookie signIn() throws Exception {
    return mockMvc
        .perform(
            post("/login").param("username", "demo").param("password", "javadropbox").with(csrf()))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getCookie("JSESSIONID");
  }

  // The demo account's folder.
  // Each server's DemoService, on the database and the store they share.
  @Test
  @DisplayName("two servers starting at once create one account and reset the files once")
  @ExtendWith(OutputCaptureExtension.class)
  void twoServersStartingAtOnce(CapturedOutput output) throws Exception {
    clear();
    DemoService other = otherServer();
    int before = resets(output);

    CountDownLatch go = new CountDownLatch(1);
    List<Future<?>> starts = new ArrayList<>();
    try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
      for (DemoService server : List.of(demo, other)) {
        starts.add(
            threads.submit(
                () -> {
                  go.await(10, TimeUnit.SECONDS);
                  server.run(new DefaultApplicationArguments());
                  return null;
                }));
      }
      go.countDown();
      for (Future<?> start : starts) {
        start.get();
      }
    }

    assertThat(users.findAll()).extracting(User::getUsername).containsExactly("demo");
    assertThat(resets(output) - before).isEqualTo(1);
    assertThat(home().resolve("Welcome.md")).exists();
  }

  @Test
  @DisplayName("a reset that is due, asked for by two servers at once, happens once")
  @ExtendWith(OutputCaptureExtension.class)
  void dueResetHappensOnce(CapturedOutput output) throws Exception {
    DemoService other = otherServer();
    other.run(new DefaultApplicationArguments());
    // The reset time passes for both servers at once.
    lastResetDaysAgo(2);
    ReflectionTestUtils.setField(demo, "nextReset", Instant.MIN);
    ReflectionTestUtils.setField(other, "nextReset", Instant.MIN);
    int before = resets(output);

    try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
      Future<?> first = threads.submit(demo::resetIfDue);
      Future<?> second = threads.submit(other::resetIfDue);
      first.get();
      second.get();
    }

    assertThat(resets(output) - before).isEqualTo(1);
    assertThat(other.nextReset()).isEqualTo(demo.nextReset()).isAfter(Instant.now());
  }

  @Test
  @DisplayName("a demo upgraded from a release that kept the reset time in a file keeps to it")
  void resetTimeFromBeforeIsKept() throws Exception {
    jdbc.update("DELETE FROM demo_reset");
    Path oldMarker = servingDir.resolve(".javadropbox/demo-reset");
    Files.createDirectories(oldMarker.getParent());
    Files.writeString(oldMarker, "");
    Files.delete(home().resolve("Welcome.md"));

    demo.run(new DefaultApplicationArguments());
    assertThat(home().resolve("Welcome.md")).as("not due yet").doesNotExist();

    Files.setLastModifiedTime(oldMarker, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
    demo.run(new DefaultApplicationArguments());
    assertThat(home().resolve("Welcome.md")).as("due").exists();
    assertThat(oldMarker).doesNotExist();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_reset", Integer.class)).isOne();
  }

  private void lastResetDaysAgo(int days) {
    jdbc.update(
        "UPDATE demo_reset SET reset_at = ?",
        Instant.now().minus(Duration.ofDays(days)).atOffset(ZoneOffset.UTC));
  }

  private static int resets(CapturedOutput output) {
    return output.getAll().split("Reset the demo's files", -1).length - 1;
  }

  private DemoService otherServer() {
    return new DemoService(
        users,
        passwordEncoder,
        fileService,
        storagePaths,
        localStore,
        searchIndex,
        jdbc,
        transactions,
        accountSessions,
        demo.username(),
        demo.password(),
        resetAt,
        demo.quotaBytes() + "B");
  }

  private Path home() {
    return localStore.path(storagePaths.home(users.findByUsername("demo").orElseThrow()).key());
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
    TestDatabase.assertUsageMatchesRows(jdbc);
    TestDatabase.wipe(jdbc);
    TestStorage.empty(servingDir);
  }
}
