package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.javadropbox.javadropbox.exception.InsufficientStorageException;
import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.model.FileHistory.ChangeType;
import com.javadropbox.javadropbox.model.RestoreMode;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.FileHistoryService;
import com.javadropbox.javadropbox.service.FileService;
import com.javadropbox.javadropbox.service.FileVersionService;
import com.javadropbox.javadropbox.service.ImportService;
import com.javadropbox.javadropbox.service.LocalFileStore;
import com.javadropbox.javadropbox.service.ShareLinkService;
import com.javadropbox.javadropbox.service.StoragePaths;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Concurrent and failing file operations, on the migrated PostgreSQL schema every test runs on: row
 * locks, deferred constraints and triggers behave there as they do in production. The spies only
 * pause or break the real services at chosen points, to make an interleaving or a failure happen on
 * purpose.
 */
@SpringBootTest(
    properties = {
      "spring.jpa.properties.hibernate.generate_statistics=true",
      "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
    })
@WithMockUser(username = "owner")
@DisplayName("File operations on PostgreSQL - concurrency and failures")
@TempServingDirectory
class FileIntegrityIntegrationTests {

  @Value("${javadropbox.serving.directory}")
  private Path servingDir;

  @Autowired private FileService fileService;
  @Autowired private ImportService imports;
  @Autowired private ShareLinkService shareLinks;
  @Autowired private FileMetadataRepository metadata;
  @Autowired private UserRepository users;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @MockitoSpyBean private FileVersionService versionService;
  @MockitoSpyBean private FileHistoryService historyService;
  @MockitoSpyBean private StoragePaths storagePaths;
  @MockitoSpyBean private LocalFileStore localStore;

  private User owner;
  // The signed-in account's folder, where its files are.
  private Path home;

  @BeforeEach
  void setUp() {
    owner = users.save(new User("owner", "unused", User.ROLE_ADMIN));
    home = localStore.path(storagePaths.home(owner).key());
  }

  @AfterEach
  void tearDown() throws IOException {
    jdbc.execute("DROP TRIGGER IF EXISTS fail_version_insert ON file_versions");
    jdbc.execute("DROP TRIGGER IF EXISTS fail_at_commit ON file_history");
    jdbc.execute("DROP TRIGGER IF EXISTS slow_folder_insert ON file_metadata");
    jdbc.execute("DROP TRIGGER IF EXISTS slow_row_insert ON file_metadata");
    TestDatabase.assertUsageMatchesRows(jdbc);
    TestDatabase.wipe(jdbc);
    TestStorage.empty(servingDir);
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
    assertThat(List.of(versionContents(id).get(3), Files.readString(home.resolve("r.txt"))))
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
    assertThat(List.of(versionContents(id).get(3), Files.readString(home.resolve("p.txt"))))
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
              Home spied = Mockito.spy((Home) invocation.callRealMethod());
              doAnswer(
                      picking -> {
                        Object picked = picking.callRealMethod();
                        if ("n_v1.txt".equals(picking.getArgument(1))) {
                          try {
                            bothPickedAName.await(2, TimeUnit.SECONDS);
                          } catch (TimeoutException | BrokenBarrierException e) {
                            // The other request is waiting for this one to finish.
                          }
                        }
                        return picked;
                      })
                  .when(spied)
                  .resolveChild(any(), anyString());
              return spied;
            })
        .when(target(storagePaths))
        .home(anyLong());
    List<Throwable> errors =
        runTogether(
            () -> fileService.restoreVersion(id, 1, RestoreMode.COPY),
            () -> fileService.restoreVersion(id, 1, RestoreMode.COPY));

    assertThat(errors).isEmpty();
    assertThat(home.resolve("n_v1.txt")).hasContent("one");
    assertThat(home.resolve("n_v1 (2).txt")).hasContent("one");
  }

  // --- folders an upload makes on the way ----------------------------------------

  @Test
  @DisplayName("two uploads into the same new folders at once both succeed, with one row each")
  void concurrentUploadsIntoNewFolders() throws Exception {
    slowDownFolderInserts();

    List<Throwable> errors =
        runTogether(() -> upload("Work/Sub", "a.txt", "A"), () -> upload("Work/Sub", "b.txt", "B"));

    assertThat(errors).isEmpty();
    assertThat(home.resolve("Work/Sub/a.txt")).hasContent("A");
    assertThat(home.resolve("Work/Sub/b.txt")).hasContent("B");
    assertThat(metadata.findAll())
        .filteredOn(m -> m.getIsDirectory())
        .extracting(m -> m.getPath())
        .containsExactlyInAnyOrder("Work", "Work/Sub");
    assertThat(
            jdbc.queryForList(
                "SELECT file_path FROM file_history WHERE change_type = 'CREATE_FOLDER'",
                String.class))
        .containsExactlyInAnyOrder("Work", "Work/Sub");
  }

  @Test
  @DisplayName("folders an upload made are removed again if their rows fail to commit")
  void failedFolderRowsLeaveNoFolders() throws Exception {
    failAtCommit();

    assertThatThrownBy(() -> upload("Work/Sub", "a.txt", "x")).isInstanceOf(RuntimeException.class);

    assertThat(home.resolve("Work")).doesNotExist();
    assertThat(metadata.findAll()).isEmpty();
  }

  @Test
  @DisplayName("a restore that recreated its file's folder and fails at commit removes both")
  void failedRestoreRemovesTheFolderItMade() throws Exception {
    upload("Work", "a.txt", "one");
    upload("Work", "a.txt", "two");
    long id = idOf("Work/a.txt");
    Files.delete(home.resolve("Work/a.txt"));
    Files.delete(home.resolve("Work"));
    metadata.deleteById(idOf("Work"));
    failAtCommit();

    assertThatThrownBy(() -> fileService.restoreVersion(id, 1, RestoreMode.OVERWRITE))
        .isInstanceOf(RuntimeException.class);

    // The file is undone before the folder it went into, so the folder is empty when its turn
    // comes.
    assertThat(home.resolve("Work")).doesNotExist();
    assertThat(metadata.findByPath(owner.getId(), "Work")).isEmpty();
  }

  @Test
  @DisplayName("a copy restore that recreated its file's folder and fails at commit removes both")
  void failedCopyRestoreRemovesTheFolderItMade() throws Exception {
    upload("Work", "a.txt", "one");
    upload("Work", "a.txt", "two");
    long id = idOf("Work/a.txt");
    Files.delete(home.resolve("Work/a.txt"));
    Files.delete(home.resolve("Work"));
    failAtCommit();

    assertThatThrownBy(() -> fileService.restoreVersion(id, 1, RestoreMode.COPY))
        .isInstanceOf(RuntimeException.class);

    assertThat(home.resolve("Work")).doesNotExist();
    assertThat(metadata.findByPath(owner.getId(), "Work/a_v1.txt")).isEmpty();
  }

  // --- importing files added outside the app ---------------------------------------

  @Test
  @DisplayName("an upload over a file copied in by hand, racing an import, makes one row")
  void uploadRacingImportMakesOneRow() throws Exception {
    Files.writeString(home.resolve("x.txt"), "by hand");
    slowDownRowInserts();

    // The import adds its row first; the upload, a moment later, finds it on its way in.
    List<Throwable> errors =
        runTogether(
            () -> imports.importFiles(owner.getId(), "test"),
            () -> {
              Thread.sleep(300);
              upload("x.txt", "uploaded");
            });

    assertThat(errors).isEmpty();
    assertThat(metadata.findPathsOf(owner.getId())).containsExactly("x.txt");
    long id = idOf("x.txt");
    assertThat(versionContents(id)).containsExactly(Map.entry(1, "by hand"));
    assertThat(home.resolve("x.txt")).hasContent("uploaded");
  }

  @Test
  @DisplayName("two uploads over a file copied in by hand at once keep one row and every version")
  void concurrentUploadsOverAnUntrackedFile() throws Exception {
    Files.writeString(home.resolve("y.txt"), "by hand");
    slowDownRowInserts();

    List<Throwable> errors = runTogether(() -> upload("y.txt", "A"), () -> upload("y.txt", "B"));

    assertThat(errors).isEmpty();
    assertThat(metadata.findPathsOf(owner.getId())).containsExactly("y.txt");
    long id = idOf("y.txt");
    assertThat(versionContents(id)).hasSize(2).containsEntry(1, "by hand");
    // Each version records the size of what it holds, not of what was there before it.
    for (Map<String, Object> version :
        jdbc.queryForList(
            "SELECT size, stored_filename FROM file_versions WHERE file_id = ?", id)) {
      Path stored =
          servingDir.resolve(".versions").resolve((String) version.get("stored_filename"));
      assertThat(((Number) version.get("size")).longValue()).isEqualTo(Files.size(stored));
    }
  }

  @Test
  @DisplayName(
      "an import racing an upload over a file copied in by hand, the upload first, keeps one row")
  void importRacingUploadMakesOneRow() throws Exception {
    Files.writeString(home.resolve("z.txt"), "by hand");
    slowDownRowInserts();

    List<Throwable> errors =
        runTogether(
            () -> upload("z.txt", "uploaded"),
            () -> {
              Thread.sleep(300);
              imports.importFiles(owner.getId(), "test");
            });

    assertThat(errors).isEmpty();
    assertThat(metadata.findPathsOf(owner.getId())).containsExactly("z.txt");
    assertThat(versionContents(idOf("z.txt"))).containsExactly(Map.entry(1, "by hand"));
  }

  @Test
  @DisplayName("an import keeps the row of a file an upload is replacing while the import checks")
  void importKeepsTheRowOfAFileBeingReplaced() throws Exception {
    Files.writeString(home.resolve("r.txt"), "by hand");
    String homeKey = storagePaths.home(owner).key();
    CountDownLatch rewalking = new CountDownLatch(1);
    CountDownLatch inTheGap = new CountDownLatch(1);
    AtomicReference<Thread> importer = new AtomicReference<>();
    AtomicInteger walks = new AtomicInteger();
    // The import's second walk, the one looking for what vanished, waits for the upload to have
    // archived the file and not yet put the new one in its place.
    doAnswer(
            invocation -> {
              if (Thread.currentThread() == importer.get()
                  && homeKey.equals(invocation.getArgument(0))
                  && walks.incrementAndGet() == 2) {
                rewalking.countDown();
                assertThat(inTheGap.await(20, TimeUnit.SECONDS)).isTrue();
              }
              return invocation.callRealMethod();
            })
        .when(target(localStore))
        .walk(anyString(), any());
    doAnswer(
            invocation -> {
              Object archived = invocation.callRealMethod();
              inTheGap.countDown();
              Thread.sleep(1000);
              return archived;
            })
        .when(target(versionService))
        .archive(any(), any(), any());

    List<Throwable> errors =
        runTogether(
            () -> {
              importer.set(Thread.currentThread());
              imports.importFiles(owner.getId(), "test");
            },
            () -> {
              assertThat(rewalking.await(20, TimeUnit.SECONDS)).isTrue();
              upload("r.txt", "uploaded");
            });

    assertThat(errors).isEmpty();
    long id = idOf("r.txt");
    assertThat(versionContents(id)).containsExactly(Map.entry(1, "by hand"));
    assertThat(home.resolve("r.txt")).hasContent("uploaded");
  }

  @Test
  @DisplayName(
      "an import while a copy restore is committing finds its row and leaves the name to it")
  void copyRestoreRacingImportKeepsItsRow() throws Exception {
    upload("n.txt", "one");
    upload("n.txt", "two");
    long id = idOf("n.txt");
    // The restore's history entry is its last insert, after the copy is in the store and its row
    // added: held there, the restore is open while the import walks and finds the copy.
    jdbc.execute(
        "CREATE OR REPLACE FUNCTION slow_insert() RETURNS trigger AS $$ BEGIN"
            + " PERFORM pg_sleep(2); RETURN NEW; END $$ LANGUAGE plpgsql");
    jdbc.execute(
        "CREATE TRIGGER slow_restore_history BEFORE INSERT ON file_history FOR EACH ROW"
            + " WHEN (NEW.change_type = 'RESTORE') EXECUTE FUNCTION slow_insert()");

    List<Throwable> errors;
    try {
      errors =
          runTogether(
              () -> fileService.restoreVersion(id, 1, RestoreMode.COPY),
              () -> {
                Thread.sleep(700);
                imports.importFiles(owner.getId(), "test");
              });
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS slow_restore_history ON file_history");
    }

    assertThat(errors).isEmpty();
    assertThat(metadata.findPathsOf(owner.getId())).containsExactlyInAnyOrder("n.txt", "n_v1.txt");
    assertThat(home.resolve("n_v1.txt")).hasContent("one");
  }

  @Test
  @DisplayName("a file deleted while an import adds its row is left without one")
  void deleteRacingImportLeavesNoRow() throws Exception {
    Files.writeString(home.resolve("gone.txt"), "by hand");
    slowDownRowInserts();

    List<Throwable> errors =
        runTogether(
            () -> imports.importFiles(owner.getId(), "test"),
            () -> {
              Thread.sleep(300);
              fileService.delete("gone.txt");
            });

    assertThat(errors).isEmpty();
    assertThat(home.resolve("gone.txt")).doesNotExist();
    assertThat(metadata.findPathsOf(owner.getId())).isEmpty();
  }

  // --- quotas ----------------------------------------------------------------------

  @Test
  @DisplayName("a delete racing a replace of the same file takes off what the file stores by then")
  void deleteRacingReplaceCountsWhatItRemoves() throws Exception {
    upload("r.txt", "one");
    CountDownLatch archived = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              archived.countDown();
              // The replace holds the file's row lock here, with its new version written.
              Thread.sleep(1000);
              return result;
            })
        .when(target(versionService))
        .archive(any(), any(), any());

    List<Throwable> errors =
        runTogether(
            () -> upload("r.txt", "a longer replacement"),
            () -> {
              assertThat(archived.await(20, TimeUnit.SECONDS)).isTrue();
              fileService.delete("r.txt");
            });

    assertThat(errors).isEmpty();
    assertThat(metadata.findPathsOf(owner.getId())).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT used_bytes FROM users WHERE id = ?", Long.class, owner.getId()))
        .isZero();
  }

  @Test
  @DisplayName("two uploads at once that each fit the quota but not together: only one is stored")
  void concurrentUploadsCantBothSqueezeIn() throws Exception {
    jdbc.update("UPDATE users SET quota_bytes = 10 WHERE id = ?", owner.getId());
    slowDownRowInserts();

    List<Throwable> errors =
        runTogether(() -> upload("a.txt", "six b."), () -> upload("b.txt", "six b."));

    assertThat(errors).singleElement().isInstanceOf(InsufficientStorageException.class);
    assertThat(metadata.findPathsOf(owner.getId())).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT used_bytes FROM users WHERE id = ?", Long.class, owner.getId()))
        .isEqualTo(6);
    try (Stream<Path> files = Files.list(home)) {
      assertThat(files.filter(Files::isRegularFile)).hasSize(1);
    }
  }

  // --- failures after the disk has changed -------------------------------------

  @Test
  @DisplayName("a replace failing after the live file was archived moves it back")
  void failureAfterArchivingRestoresTheLiveFile() throws Exception {
    upload("b.txt", "precious");
    failVersionInserts();

    assertThatThrownBy(() -> upload("b.txt", "replacement"))
        .hasMessageContaining("simulated failure");

    assertThat(home.resolve("b.txt")).hasContent("precious");
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

    assertThat(home.resolve("c.txt")).hasContent("two");
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

    assertThat(home.resolve("a.txt")).hasContent("old");
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

    assertThat(home.resolve("d.txt")).hasContent("old");
    assertThat(storedVersionFiles()).isEmpty();
  }

  @Test
  @DisplayName("a new upload failing at commit leaves no untracked file behind")
  void failedNewUploadLeavesNothing() throws Exception {
    failAtCommit();

    assertThatThrownBy(() -> upload("e.txt", "new")).isInstanceOf(RuntimeException.class);

    assertThat(home.resolve("e.txt")).doesNotExist();
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

    try (Stream<Path> entries = Files.list(home)) {
      assertThat(entries.map(p -> p.getFileName().toString())).containsExactly("k.txt");
    }
    assertThat(metadata.findByPath(owner.getId(), "k_v1.txt")).isEmpty();
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
   * Lets two deletes both resolve the item before either locks its rows, so that they really run at
   * once; the row locks then make them take turns.
   */
  private void holdSecondVersionCleanupUntilFirstCommits() {
    CyclicBarrier bothStarted = new CyclicBarrier(2);
    doAnswer(
            invocation -> {
              try {
                bothStarted.await(5, TimeUnit.SECONDS);
              } catch (TimeoutException | BrokenBarrierException e) {
                // The other delete is not resolving alongside this one.
              }
              return invocation.callRealMethod();
            })
        .when(target(storagePaths))
        .home(anyLong());
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

  /**
   * Makes every insert of a folder's row wait a second first, so that requests started together
   * have all looked at the store before any of them commits.
   */
  private void slowDownFolderInserts() {
    jdbc.execute(
        "CREATE OR REPLACE FUNCTION slow_insert() RETURNS trigger AS $$ BEGIN"
            + " PERFORM pg_sleep(1); RETURN NEW; END $$ LANGUAGE plpgsql");
    jdbc.execute(
        "CREATE TRIGGER slow_folder_insert BEFORE INSERT ON file_metadata FOR EACH ROW"
            + " WHEN (NEW.is_directory) EXECUTE FUNCTION slow_insert()");
  }

  /** Like {@link #slowDownFolderInserts}, for every row. */
  private void slowDownRowInserts() {
    jdbc.execute(
        "CREATE OR REPLACE FUNCTION slow_insert() RETURNS trigger AS $$ BEGIN"
            + " PERFORM pg_sleep(1); RETURN NEW; END $$ LANGUAGE plpgsql");
    jdbc.execute(
        "CREATE TRIGGER slow_row_insert BEFORE INSERT ON file_metadata FOR EACH ROW"
            + " EXECUTE FUNCTION slow_insert()");
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

  /** Runs both at once, signed in as the test is, and returns what they threw. */
  private List<Throwable> runTogether(Work a, Work b) throws InterruptedException {
    List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
    List<Thread> threads = new ArrayList<>();
    SecurityContext signedIn = SecurityContextHolder.getContext();
    for (Work work : List.of(a, b)) {
      threads.add(
          new Thread(
              () -> {
                SecurityContextHolder.setContext(signedIn);
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
    return metadata.findByPath(owner.getId(), path).orElseThrow().getId();
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
}
