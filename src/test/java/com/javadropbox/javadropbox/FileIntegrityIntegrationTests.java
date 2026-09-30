package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.model.FileHistory.ChangeType;
import com.javadropbox.javadropbox.model.RestoreMode;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.service.FileHistoryService;
import com.javadropbox.javadropbox.service.FileService;
import com.javadropbox.javadropbox.service.FileVersionService;
import com.javadropbox.javadropbox.service.StoragePaths;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Concurrent and failing file operations, on the migrated PostgreSQL schema: row locks, deferred
 * constraints and triggers behave there as they do in production. The spies only pause or break the
 * real services at chosen points, to make an interleaving or a failure happen on purpose.
 */
@SpringBootTest(
    properties = {
      "spring.jpa.properties.hibernate.generate_statistics=true",
      "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
    })
@Testcontainers
@DisplayName("File operations on PostgreSQL - concurrency and failures")
class FileIntegrityIntegrationTests {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(PostgresTestSupport.IMAGE);

  @TempDir static Path servingDir;

  @DynamicPropertySource
  static void overrideDatasource(DynamicPropertyRegistry registry) {
    PostgresTestSupport.register(registry, POSTGRES);
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
  }

  @Autowired private FileService fileService;
  @Autowired private FileMetadataRepository metadata;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @MockitoSpyBean private FileVersionService versionService;
  @MockitoSpyBean private FileHistoryService historyService;
  @MockitoSpyBean private StoragePaths storagePaths;

  @AfterEach
  void tearDown() throws IOException {
    jdbc.execute("DROP TRIGGER IF EXISTS fail_version_insert ON file_versions");
    jdbc.execute("DROP TRIGGER IF EXISTS fail_at_commit ON file_history");
    TestDatabase.wipe(jdbc);
    try (Stream<Path> entries = Files.list(servingDir)) {
      for (Path entry : entries.toList()) {
        deleteTree(entry);
      }
    }
  }

  // --- concurrent replaces -----------------------------------------------------

  @Test
  @DisplayName("two replaces of one file at once each keep what they replaced as its own version")
  void concurrentReplacesKeepEveryVersion() throws Exception {
    upload("r.txt", "one");
    upload("r.txt", "two");
    long id = idOf("r.txt");

    holdSecondArchiveUntilFirstCommits();
    List<Throwable> errors =
        runTogether(() -> upload("r.txt", "from A"), () -> upload("r.txt", "from B"));

    assertThat(errors).isEmpty();
    assertThat(versionContents(id).keySet()).containsExactly(1, 2, 3);
    assertThat(versionContents(id)).containsEntry(1, "one").containsEntry(2, "two");
    assertThat(List.of(versionContents(id).get(3), Files.readString(servingDir.resolve("r.txt"))))
        .containsExactlyInAnyOrder("from A", "from B");
    assertThat(metadata.findById(id).orElseThrow().getCurrentVersion()).isEqualTo(4);
  }

  @Test
  @DisplayName("a replace racing an in-place restore keeps both replaced contents")
  void replaceRacingRestoreKeepsEveryVersion() throws Exception {
    upload("p.txt", "one");
    upload("p.txt", "two");
    long id = idOf("p.txt");

    holdSecondArchiveUntilFirstCommits();
    List<Throwable> errors =
        runTogether(
            () -> upload("p.txt", "from A"),
            () -> fileService.restoreVersion(id, 1, RestoreMode.OVERWRITE));

    assertThat(errors).isEmpty();
    assertThat(versionContents(id).keySet()).containsExactly(1, 2, 3);
    assertThat(versionContents(id)).containsEntry(1, "one").containsEntry(2, "two");
    assertThat(List.of(versionContents(id).get(3), Files.readString(servingDir.resolve("p.txt"))))
        .containsExactlyInAnyOrder("from A", "one");
  }

  @Test
  @DisplayName("the database refuses a second row for the same version of a file")
  void duplicateVersionNumbersAreRefused() throws Exception {
    upload("u.txt", "one");
    upload("u.txt", "two");
    long id = idOf("u.txt");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO file_versions (file_id, version, stored_filename) VALUES (?, 1, ?)",
                    id,
                    id + "/v1-copy"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("two restores of one version as a copy at once create two copies")
  void concurrentCopyRestoresBothSucceed() throws Exception {
    upload("n.txt", "one");
    upload("n.txt", "two");
    long id = idOf("n.txt");

    // Both requests pick a name for the copy before either has created it, unless one waits.
    CyclicBarrier bothPickedAName = new CyclicBarrier(2);
    doAnswer(
            invocation -> {
              Object picked = invocation.callRealMethod();
              if ("n_v1.txt".equals(invocation.getArgument(1))) {
                try {
                  bothPickedAName.await(2, TimeUnit.SECONDS);
                } catch (TimeoutException | BrokenBarrierException e) {
                  // The other request is waiting for this one to finish.
                }
              }
              return picked;
            })
        .when(target(storagePaths))
        .resolveChild(any(), anyString());
    List<Throwable> errors =
        runTogether(
            () -> fileService.restoreVersion(id, 1, RestoreMode.COPY),
            () -> fileService.restoreVersion(id, 1, RestoreMode.COPY));

    assertThat(errors).isEmpty();
    assertThat(servingDir.resolve("n_v1.txt")).hasContent("one");
    assertThat(servingDir.resolve("n_v1 (2).txt")).hasContent("one");
  }

  // --- failures after the disk has changed -------------------------------------

  @Test
  @DisplayName("a replace failing after the live file was archived moves it back")
  void failureAfterArchivingRestoresTheLiveFile() throws Exception {
    upload("b.txt", "precious");
    failVersionInserts();

    assertThatThrownBy(() -> upload("b.txt", "replacement"))
        .hasMessageContaining("simulated failure");

    assertThat(servingDir.resolve("b.txt")).hasContent("precious");
    assertThat(storedVersionFiles()).isEmpty();
    assertThat(scratchFiles()).isEmpty();
  }

  @Test
  @DisplayName("an in-place restore failing after the live file was archived moves it back")
  void failedRestoreRestoresTheLiveFile() throws Exception {
    upload("c.txt", "one");
    upload("c.txt", "two");
    long id = idOf("c.txt");
    failVersionInserts();

    assertThatThrownBy(() -> fileService.restoreVersion(id, 1, RestoreMode.OVERWRITE))
        .hasMessageContaining("simulated failure");

    assertThat(servingDir.resolve("c.txt")).hasContent("two");
    assertThat(storedVersionFiles()).containsExactly(id + "/v1");
  }

  @Test
  @DisplayName("a replace failing after the new content went live puts the old content back")
  void failureAfterTheSwapRestoresTheOldContent() throws Exception {
    upload("a.txt", "old");
    long id = idOf("a.txt");
    doThrow(new IllegalStateException("simulated failure"))
        .when(target(historyService))
        .recordSuccess(any(), eq(ChangeType.UPLOAD), any(), any());

    assertThatThrownBy(() -> upload("a.txt", "new")).hasMessageContaining("simulated failure");

    assertThat(servingDir.resolve("a.txt")).hasContent("old");
    assertThat(storedVersionFiles()).isEmpty();
    assertThat(metadata.findById(id).orElseThrow().getCurrentVersion()).isEqualTo(1);

    Mockito.reset(target(historyService));
    upload("a.txt", "newer");
    assertThat(versionContents(id)).containsExactly(Map.entry(1, "old"));
  }

  @Test
  @DisplayName("a replace failing at commit puts the old content back")
  void failureAtCommitRestoresTheOldContent() throws Exception {
    upload("d.txt", "old");
    failAtCommit();

    assertThatThrownBy(() -> upload("d.txt", "new")).isInstanceOf(RuntimeException.class);

    assertThat(servingDir.resolve("d.txt")).hasContent("old");
    assertThat(storedVersionFiles()).isEmpty();
  }

  @Test
  @DisplayName("a new upload failing at commit leaves no untracked file behind")
  void failedNewUploadLeavesNothing() throws Exception {
    failAtCommit();

    assertThatThrownBy(() -> upload("e.txt", "new")).isInstanceOf(RuntimeException.class);

    assertThat(servingDir.resolve("e.txt")).doesNotExist();
    assertThat(scratchFiles()).isEmpty();
  }

  @Test
  @DisplayName("a restore as a copy that fails leaves neither the copy nor a scratch file")
  void failedCopyRestoreLeavesNothing() throws Exception {
    upload("k.txt", "one");
    upload("k.txt", "two");
    long id = idOf("k.txt");
    doThrow(new IllegalStateException("simulated failure"))
        .when(target(historyService))
        .recordSuccess(any(), eq(ChangeType.RESTORE), any(), any());

    assertThatThrownBy(() -> fileService.restoreVersion(id, 1, RestoreMode.COPY))
        .hasMessageContaining("simulated failure");

    try (Stream<Path> entries = Files.list(servingDir)) {
      assertThat(entries.map(p -> p.getFileName().toString()))
          .containsExactlyInAnyOrder("k.txt", ".versions");
    }
    assertThat(metadata.findByPath("k_v1.txt")).isEmpty();
  }

  // --- deleting ----------------------------------------------------------------

  @Test
  @DisplayName("deleting a folder takes as many statements for many items as for a few")
  void folderDeleteStatementsDoNotGrowWithItems() throws Exception {
    long few = statementsToDelete(folderWithVersionedFiles("few", 2));
    long many = statementsToDelete(folderWithVersionedFiles("many", 8));

    assertThat(many).isEqualTo(few);
    assertThat(metadata.findAll()).isEmpty();
    assertThat(storedVersionFiles()).isEmpty();
  }

  @Test
  @DisplayName("of two deletes of one file at once, the second finds nothing to delete")
  void concurrentDeletesOfOneFile() throws Exception {
    upload("d.txt", "x");

    holdSecondVersionCleanupUntilFirstCommits();
    List<Throwable> errors =
        runTogether(() -> fileService.delete("d.txt"), () -> fileService.delete("d.txt"));

    assertThat(errors).singleElement().isInstanceOf(NotFoundException.class);
    assertThat(successfulDeletionsOf("d.txt")).isEqualTo(1);
  }

  @Test
  @DisplayName("of two deletes of one folder at once, the second finds nothing to delete")
  void concurrentDeletesOfOneFolder() throws Exception {
    fileService.createFolder("", "dir");
    upload("dir", "f.txt", "x");

    holdSecondVersionCleanupUntilFirstCommits();
    List<Throwable> errors =
        runTogether(() -> fileService.delete("dir"), () -> fileService.delete("dir"));

    assertThat(errors).singleElement().isInstanceOf(NotFoundException.class);
    assertThat(successfulDeletionsOf("dir")).isEqualTo(1);
  }

  // --- helpers ------------------------------------------------------------------

  private final CountDownLatch firstFinished = new CountDownLatch(1);

  /**
   * Holds the second of two deletes, once both have checked that the item exists, until the first
   * has committed.
   */
  private void holdSecondVersionCleanupUntilFirstCommits() {
    CyclicBarrier bothStarted = new CyclicBarrier(2);
    AtomicInteger arrivals = new AtomicInteger();
    doAnswer(
            invocation -> {
              boolean first = arrivals.incrementAndGet() == 1;
              bothStarted.await(20, TimeUnit.SECONDS);
              if (!first) {
                assertThat(firstFinished.await(20, TimeUnit.SECONDS)).isTrue();
              }
              return invocation.callRealMethod();
            })
        .when(target(versionService))
        .discardAllAtOrBelow(any());
  }

  private int successfulDeletionsOf(String path) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM file_history WHERE change_type = 'DELETE' AND success"
            + " AND file_path = ?",
        Integer.class,
        path);
  }

  /**
   * Makes the first request to reach {@code archive()} wait there for up to two seconds for the
   * other one. If the other one gets there too -- i.e. both read the file's row before either
   * changed it -- it is held until the first has committed, the interleaving that used to give both
   * the same version number. If it doesn't, it is waiting for the first to finish, as it should.
   */
  private void holdSecondArchiveUntilFirstCommits() throws IOException {
    CyclicBarrier bothInArchive = new CyclicBarrier(2);
    AtomicInteger arrivals = new AtomicInteger();
    doAnswer(
            invocation -> {
              boolean first = arrivals.incrementAndGet() == 1;
              try {
                bothInArchive.await(2, TimeUnit.SECONDS);
              } catch (TimeoutException | BrokenBarrierException e) {
                // The other request is not in archive() alongside this one.
              }
              if (!first) {
                assertThat(firstFinished.await(20, TimeUnit.SECONDS)).isTrue();
              }
              return invocation.callRealMethod();
            })
        .when(target(versionService))
        .archive(any(), any(), any());
  }

  private String folderWithVersionedFiles(String folder, int files) throws IOException {
    fileService.createFolder("", folder);
    for (int i = 0; i < files; i++) {
      upload(folder, "f" + i + ".txt", "one");
      upload(folder, "f" + i + ".txt", "two");
    }
    return folder;
  }

  private long statementsToDelete(String path) throws IOException {
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();
    fileService.delete(path);
    return statistics.getPrepareStatementCount();
  }

  /** Makes every insert into file_versions fail, as a lost connection or lock timeout would. */
  private void failVersionInserts() {
    jdbc.execute(
        "CREATE OR REPLACE FUNCTION simulated_failure() RETURNS trigger AS $$ BEGIN"
            + " RAISE EXCEPTION 'simulated failure'; END $$ LANGUAGE plpgsql");
    jdbc.execute(
        "CREATE TRIGGER fail_version_insert BEFORE INSERT ON file_versions FOR EACH ROW"
            + " EXECUTE FUNCTION simulated_failure()");
  }

  /** Makes the commit of any successful change fail, through a deferred constraint trigger. */
  private void failAtCommit() {
    jdbc.execute(
        "CREATE OR REPLACE FUNCTION simulated_failure() RETURNS trigger AS $$ BEGIN"
            + " RAISE EXCEPTION 'simulated failure'; END $$ LANGUAGE plpgsql");
    jdbc.execute(
        "CREATE CONSTRAINT TRIGGER fail_at_commit AFTER INSERT ON file_history"
            + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.success)"
            + " EXECUTE FUNCTION simulated_failure()");
  }

  /** Every stored version file, relative to the version store. */
  private List<String> storedVersionFiles() throws IOException {
    Path store = servingDir.resolve(".versions");
    if (!Files.exists(store)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(store)) {
      return walk.filter(Files::isRegularFile)
          .map(p -> store.relativize(p).toString().replace('\\', '/'))
          .toList();
    }
  }

  private List<Path> scratchFiles() throws IOException {
    try (Stream<Path> walk = Files.walk(servingDir)) {
      return walk.filter(p -> p.getFileName().toString().startsWith(".upload-")).toList();
    }
  }

  private interface Work {
    void run() throws Exception;
  }

  /** Runs both at once and returns what they threw. */
  private List<Throwable> runTogether(Work a, Work b) throws InterruptedException {
    List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
    List<Thread> threads = new ArrayList<>();
    for (Work work : List.of(a, b)) {
      threads.add(
          new Thread(
              () -> {
                try {
                  work.run();
                } catch (Throwable e) {
                  errors.add(e);
                } finally {
                  firstFinished.countDown();
                }
              }));
    }
    threads.forEach(Thread::start);
    for (Thread thread : threads) {
      thread.join(30_000);
    }
    return errors;
  }

  /** Each version number of a file with the content of its stored copy; duplicates are joined. */
  private Map<Integer, String> versionContents(long id) {
    Map<Integer, String> contents = new TreeMap<>();
    jdbc.query(
        "SELECT version, stored_filename FROM file_versions WHERE file_id = ? ORDER BY version",
        rs -> {
          Path stored = servingDir.resolve(".versions").resolve(rs.getString(2));
          contents.merge(
              rs.getInt(1),
              Files.isRegularFile(stored) ? read(stored) : "<missing>",
              (x, y) -> x + " | " + y);
        },
        id);
    return contents;
  }

  private void upload(String name, String content) throws IOException {
    upload("", name, content);
  }

  private void upload(String folder, String name, String content) throws IOException {
    fileService.upload(
        new MultipartFile[] {
          new MockMultipartFile(
              "files", name, "text/plain", content.getBytes(StandardCharsets.UTF_8))
        },
        folder);
  }

  private long idOf(String path) {
    return metadata.findByPath(path).orElseThrow().getId();
  }

  @SuppressWarnings("unchecked")
  private static <T> T target(T bean) {
    return (T) AopTestUtils.getUltimateTargetObject(bean);
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteTree(Path path) throws IOException {
    try (Stream<Path> walk = Files.walk(path)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }
}
