package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.javadropbox.javadropbox.model.RestoreMode;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.service.FileService;
import com.javadropbox.javadropbox.service.FileVersionService;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
@SpringBootTest
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
  @MockitoSpyBean private FileVersionService versionService;

  @AfterEach
  void tearDown() throws IOException {
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

  // --- helpers ------------------------------------------------------------------

  private final CountDownLatch firstFinished = new CountDownLatch(1);

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
    fileService.upload(
        new MultipartFile[] {
          new MockMultipartFile(
              "files", name, "text/plain", content.getBytes(StandardCharsets.UTF_8))
        },
        "");
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
