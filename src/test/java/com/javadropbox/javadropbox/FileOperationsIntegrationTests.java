package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.javadropbox.javadropbox.model.FileHistory;
import com.javadropbox.javadropbox.model.FileHistory.ChangeType;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.FileVersion;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileHistoryRepository;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.FileVersionRepository;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.FileService;
import com.javadropbox.javadropbox.service.ImportService;
import com.javadropbox.javadropbox.service.LocalFileStore;
import com.javadropbox.javadropbox.service.StoragePaths;
import com.javadropbox.javadropbox.service.StorageSweeper;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

@SpringBootTest(properties = "javadropbox.versions.max-retained=2")
@AutoConfigureMockMvc
@WithMockUser(username = "owner")
@DisplayName("File operations")
@TempServingDirectory
class FileOperationsIntegrationTests {

  @Value("${javadropbox.serving.directory}")
  private Path servingDir;

  @Autowired private MockMvc mockMvc;
  @Autowired private FileService fileService;
  @Autowired private UserRepository users;
  @Autowired private FileMetadataRepository metadata;
  @Autowired private FileVersionRepository versions;
  @Autowired private FileHistoryRepository history;
  @Autowired private StorageSweeper sweeper;
  @Autowired private StoragePaths storagePaths;
  @Autowired private LocalFileStore localStore;
  @Autowired private ImportService imports;

  private User owner;
  // The signed-in account's folder, where its files are.
  private Path home;

  @BeforeEach
  void setUp() {
    owner = users.save(new User("owner", "unused", "ROLE_ADMIN"));
    home = localStore.path(storagePaths.home(owner).key());
  }

  @Autowired private JdbcTemplate jdbc;

  @AfterEach
  void tearDown() throws IOException {
    TestDatabase.assertUsageMatchesRows(jdbc);
    ContentHashes.assertTrustedHashesMatch(jdbc, servingDir);
    TestDatabase.wipe(jdbc);
    TestStorage.empty(servingDir);
  }

  // --- uploading -------------------------------------------------------------

  @Test
  @DisplayName("an empty file is stored, listed and versioned like any other")
  void emptyFileIsStored() throws Exception {
    mockMvc
        .perform(
            multipart("/api/files")
                .file(new MockMultipartFile("files", "empty.txt", "text/plain", new byte[0]))
                .param("path", "")
                .with(csrf().asHeader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("Uploaded 1 file"));

    assertThat(home.resolve("empty.txt")).isEmptyFile();
    mockMvc
        .perform(get("/api/files"))
        .andExpect(jsonPath("$[?(@.name == 'empty.txt')].size").value(0))
        .andExpect(jsonPath("$[?(@.name == 'empty.txt')].id").isNotEmpty());

    upload("", "empty.txt", "content");
    assertThat(versions.findAll()).extracting(FileVersion::getSize).containsExactly(0L);
  }

  @Test
  @DisplayName("a part without a file name is skipped and not counted as uploaded")
  void partWithoutFileNameIsSkipped() throws Exception {
    mockMvc
        .perform(
            multipart("/api/files")
                .file(new MockMultipartFile("files", "", "application/octet-stream", new byte[0]))
                .file(new MockMultipartFile("files", "real.txt", "text/plain", "x".getBytes()))
                .param("path", "")
                .with(csrf().asHeader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("Uploaded 1 file"));

    assertThat(metadata.findAll()).extracting(FileMetadata::getPath).containsExactly("real.txt");
  }

  @Test
  @DisplayName("uploading into a path that runs through a file is a 400, recorded as a failure")
  void uploadThroughAFileIsRefused() throws Exception {
    upload("", "a.txt", "x");

    for (String folder : List.of("a.txt", "a.txt/sub")) {
      mockMvc
          .perform(
              multipart("/api/files")
                  .file(new MockMultipartFile("files", "b.txt", "text/plain", "y".getBytes()))
                  .param("path", folder)
                  .with(csrf().asHeader()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("\"a.txt\" is a file, not a folder"));
    }

    assertThat(history.findAll())
        .filteredOn(h -> !h.isSuccess())
        .extracting(FileHistory::getChangeType, FileHistory::getFilePath)
        .containsExactly(
            Tuple.tuple(ChangeType.UPLOAD, "a.txt/b.txt"),
            Tuple.tuple(ChangeType.UPLOAD, "a.txt/sub/b.txt"));
    assertThat(home.resolve("a.txt")).hasContent("x");
  }

  @Test
  @DisplayName("folders an upload makes on the way are listed with an id, an owner and history")
  void foldersMadeByAnUploadHaveRows() throws Exception {
    upload("Work/Sub", "report.txt", "x");

    mockMvc
        .perform(get("/api/files"))
        .andExpect(jsonPath("$[?(@.name == 'Work')].id").value((int) idOf("Work")))
        .andExpect(jsonPath("$[?(@.name == 'Work')].ownerName").value("owner"))
        .andExpect(
            jsonPath("$[?(@.name == 'Work')].children[?(@.name == 'Sub')].id")
                .value((int) idOf("Work/Sub")))
        .andExpect(
            jsonPath("$[?(@.name == 'Work')].children[?(@.name == 'Sub')].ownerName")
                .value("owner"));
    assertThat(metadata.findAll())
        .extracting(FileMetadata::getPath, FileMetadata::getIsDirectory)
        .containsExactlyInAnyOrder(
            Tuple.tuple("Work", true),
            Tuple.tuple("Work/Sub", true),
            Tuple.tuple("Work/Sub/report.txt", false));
    assertThat(history.findAll())
        .filteredOn(h -> h.getChangeType() == ChangeType.CREATE_FOLDER)
        .extracting(FileHistory::getFilePath, FileHistory::isSuccess)
        .containsExactly(Tuple.tuple("Work", true), Tuple.tuple("Work/Sub", true));
  }

  @Test
  @DisplayName("uploading into folders that are already there adds no folder rows or history")
  void uploadIntoExistingFoldersAddsNoFolders() throws Exception {
    createFolder("", "Work");
    upload("Work", "a.txt", "x");
    upload("Work/Sub", "b.txt", "y");
    upload("Work/Sub", "c.txt", "z");

    assertThat(metadata.findAll()).filteredOn(FileMetadata::getIsDirectory).hasSize(2);
    assertThat(history.findAll())
        .filteredOn(h -> h.getChangeType() == ChangeType.CREATE_FOLDER)
        .extracting(FileHistory::getFilePath)
        .containsExactly("Work", "Work/Sub");
  }

  @Test
  @DisplayName("a file listed but removed from the disk by hand is still a file: no folder in it")
  void listedFileGoneFromTheDiskIsNotAFolder() throws Exception {
    upload("", "Work", "one");
    upload("", "Work", "two");
    long id = idOf("Work");
    Files.delete(home.resolve("Work"));

    mockMvc
        .perform(
            multipart("/api/files")
                .file(new MockMultipartFile("files", "a.txt", "text/plain", "x".getBytes()))
                .param("path", "Work")
                .with(csrf().asHeader()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("\"Work\" is a file, not a folder"));

    FileMetadata file = metadata.findByPath(owner.getId(), "Work").orElseThrow();
    assertThat(file.getId()).isEqualTo(id);
    assertThat(file.getIsDirectory()).isFalse();
    assertThat(versions.findAll()).hasSize(1);
  }

  @Test
  @DisplayName(
      "an upload into a listed folder removed from the disk by hand makes it again, same row")
  void uploadIntoAListedFolderGoneFromTheDisk() throws Exception {
    createFolder("", "Work");
    long id = idOf("Work");
    Files.delete(home.resolve("Work"));

    upload("Work", "a.txt", "x");

    assertThat(home.resolve("Work/a.txt")).hasContent("x");
    assertThat(idOf("Work")).isEqualTo(id);
    assertThat(history.findAll())
        .filteredOn(h -> h.getChangeType() == ChangeType.CREATE_FOLDER)
        .extracting(FileHistory::getFilePath)
        .containsExactly("Work");
  }

  @Test
  @DisplayName(
      "restoring a file whose folder is now listed as a file, gone from the disk, is a 400")
  void restoreUnderAListedFileIsRefused() throws Exception {
    upload("Work", "a.txt", "one");
    upload("Work", "a.txt", "two");
    long id = idOf("Work/a.txt");
    deleteFolderByHand(home.resolve("Work"));
    jdbc.update(
        "UPDATE file_metadata SET is_directory = false WHERE user_id = ? AND path = 'Work'",
        owner.getId());

    mockMvc
        .perform(
            post("/api/files/" + id + "/versions/1/restore")
                .param("mode", "OVERWRITE")
                .with(csrf()))
        .andExpect(status().isBadRequest());

    assertThat(home.resolve("Work")).doesNotExist();
  }

  @Test
  @DisplayName("a folder listed but removed from the disk by hand can't be replaced by a file")
  void listedFolderGoneFromTheDiskIsNotAFile() throws Exception {
    createFolder("", "Photos");
    Files.delete(home.resolve("Photos"));

    mockMvc
        .perform(
            multipart("/api/files")
                .file(new MockMultipartFile("files", "Photos", "text/plain", "x".getBytes()))
                .param("path", "")
                .with(csrf().asHeader()))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            post("/api/folders")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("path", "")
                .param("name", "Photos")
                .with(csrf()))
        .andExpect(status().isConflict());

    assertThat(metadata.findByPath(owner.getId(), "Photos").orElseThrow().getIsDirectory())
        .isTrue();
  }

  @Test
  @DisplayName("restoring a file whose folder was removed outside the app gives the folder a row")
  void restoreRecreatesFolderWithRow() throws Exception {
    upload("Work", "a.txt", "one");
    upload("Work", "a.txt", "two");
    long id = idOf("Work/a.txt");
    deleteFolderByHand(home.resolve("Work"));
    metadata.deleteById(idOf("Work"));

    restore(id, 1, "OVERWRITE");

    assertThat(home.resolve("Work/a.txt")).hasContent("one");
    assertThat(metadata.findByPath(owner.getId(), "Work"))
        .get()
        .extracting(FileMetadata::getIsDirectory)
        .isEqualTo(true);
  }

  @Test
  @DisplayName("restoring a copy of a file whose folder was removed outside the app recreates it")
  void restoreAsCopyRecreatesFolderWithRow() throws Exception {
    upload("Work", "a.txt", "one");
    upload("Work", "a.txt", "two");
    long id = idOf("Work/a.txt");
    long workId = idOf("Work");
    deleteFolderByHand(home.resolve("Work"));

    restore(id, 1, "COPY");

    assertThat(home.resolve("Work/a_v1.txt")).hasContent("one");
    // The folder is made again in the store, and keeps the row it had.
    FileMetadata folder = metadata.findByPath(owner.getId(), "Work").orElseThrow();
    assertThat(folder.getIsDirectory()).isTrue();
    assertThat(folder.getId()).isEqualTo(workId);
  }

  @Test
  @DisplayName(
      "an item removed from the disk by hand is still listed, and deleting it drops its rows")
  void deletingAnItemGoneFromTheDiskDropsItsRows() throws Exception {
    upload("Gone", "a.txt", "one");
    upload("Gone", "a.txt", "two");
    deleteFolderByHand(home.resolve("Gone"));

    deletePath("Gone");

    assertThat(metadata.findAll()).isEmpty();
    assertThat(versions.findAll()).isEmpty();
    mockMvc
        .perform(delete("/api/files").param("path", "Gone").with(csrf()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("the list comes from the database: a file copied in is listed once imported")
  void listingReadsRowsNotTheDisk() throws Exception {
    upload("", "kept.txt", "x");
    upload("", "uploaded.txt", "x");
    Files.writeString(home.resolve("copied in.txt"), "by hand");
    Files.delete(home.resolve("uploaded.txt"));

    // Its row is still there, so it is listed; the file copied in has none yet.
    mockMvc
        .perform(get("/api/files"))
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[?(@.name == 'uploaded.txt')]").isNotEmpty())
        .andExpect(jsonPath("$[?(@.name == 'copied in.txt')]").isEmpty());

    imports.importFiles(owner.getId(), "test");

    mockMvc
        .perform(get("/api/files"))
        .andExpect(jsonPath("$[?(@.name == 'copied in.txt')].id").isNotEmpty());
  }

  // --- content hashes ----------------------------------------------------------

  @Test
  @DisplayName(
      "an upload records the SHA-256 of its content, and a replace moves it to the version")
  void uploadsRecordTheirHash() throws Exception {
    upload("", "h.txt", "first");
    assertThat(metadata.findByPath(owner.getId(), "h.txt").orElseThrow().getContentHash())
        .isEqualTo(sha256("first"));

    upload("", "h.txt", "second");

    assertThat(metadata.findByPath(owner.getId(), "h.txt").orElseThrow().getContentHash())
        .isEqualTo(sha256("second"));
    assertThat(versions.findAll())
        .singleElement()
        .extracting(FileVersion::getContentHash)
        .isEqualTo(
            sha256("first")); // The live file and its version: the check after each test finds them
    // where it looks.
    assertThat(ContentHashes.assertTrustedHashesMatch(jdbc, servingDir)).isEqualTo(2);
  }

  @Test
  @DisplayName("a restore, in place or as a copy, gives the file its version's hash")
  void restoresCarryTheVersionsHash() throws Exception {
    upload("", "r.txt", "one");
    upload("", "r.txt", "two");
    long id = idOf("r.txt");

    restore(id, 1, "COPY");
    restore(id, 1, "OVERWRITE");

    assertThat(metadata.findByPath(owner.getId(), "r_v1.txt").orElseThrow().getContentHash())
        .isEqualTo(sha256("one"));
    assertThat(metadata.findById(id).orElseThrow().getContentHash()).isEqualTo(sha256("one"));
    // What the restore replaced is kept as a version, with its own hash.
    assertThat(versions.findAll()).extracting(FileVersion::getContentHash).contains(sha256("two"));
  }

  @Test
  @DisplayName("a file changed outside the app to another length is archived without a hash")
  void contentChangedByHandIsArchivedWithoutAHash() throws Exception {
    upload("", "e.txt", "app");
    Files.writeString(home.resolve("e.txt"), "edited by hand");

    upload("", "e.txt", "new");

    assertThat(versions.findAll()).singleElement().extracting(FileVersion::getContentHash).isNull();
  }

  @Test
  @DisplayName("a file edited by hand to the same length is archived without a hash")
  void sameLengthEditIsArchivedWithoutAHash() throws Exception {
    upload("", "s.txt", "abc");
    later(Files.writeString(home.resolve("s.txt"), "xyz"));

    upload("", "s.txt", "new");

    assertThat(versions.findAll()).singleElement().extracting(FileVersion::getContentHash).isNull();
  }

  @Test
  @DisplayName("a stored version edited by hand is restored without a hash")
  void versionEditedByHandIsRestoredWithoutAHash() throws Exception {
    upload("", "v.txt", "one");
    upload("", "v.txt", "two");
    long id = idOf("v.txt");
    later(Files.writeString(servingDir.resolve(".versions/" + id + "/v1"), "ONE"));

    restore(id, 1, "COPY");

    assertThat(metadata.findByPath(owner.getId(), "v_v1.txt").orElseThrow().getContentHash())
        .isNull();
  }

  @Test
  @DisplayName("an upload over a file with no row hashes the upload, and keeps no hash for the old")
  void uploadOverAnUntrackedFile() throws Exception {
    Files.writeString(home.resolve("u.txt"), "by hand");

    upload("", "u.txt", "uploaded");

    assertThat(metadata.findByPath(owner.getId(), "u.txt").orElseThrow().getContentHash())
        .isEqualTo(sha256("uploaded"));
    assertThat(versions.findAll()).singleElement().extracting(FileVersion::getContentHash).isNull();
  }

  @Test
  @DisplayName("a file copied in by hand and imported has no hash, as its content was never read")
  void importedFilesHaveNoHash() throws Exception {
    Files.writeString(home.resolve("copied.txt"), "by hand");

    imports.importFiles(owner.getId(), "test");

    assertThat(metadata.findByPath(owner.getId(), "copied.txt").orElseThrow().getContentHash())
        .isNull();
  }

  // --- deleting --------------------------------------------------------------

  @Test
  @DisplayName("a folder can be deleted and created again without breaking the file tree")
  void deleteThenRecreateFolder() throws Exception {
    createFolder("", "a");
    deletePath("a");
    createFolder("", "a");

    mockMvc.perform(get("/api/files")).andExpect(status().isOk());
    assertThat(metadata.findAll()).extracting(FileMetadata::getPath).containsExactly("a");
  }

  @Test
  @DisplayName("deleting a file removes its row and stored versions but keeps its history")
  void deleteRemovesRowAndVersions() throws Exception {
    upload("", "report.txt", "one");
    upload("", "report.txt", "two");
    assertThat(versionFiles()).hasSize(1);

    deletePath("report.txt");

    assertThat(metadata.findByPath(owner.getId(), "report.txt")).isEmpty();
    assertThat(versions.findAll()).isEmpty();
    assertThat(versionFiles()).isEmpty();
    assertThat(history.findAll())
        .filteredOn(h -> "report.txt".equals(h.getFilePath()))
        .extracting(FileHistory::getChangeType)
        .contains(ChangeType.UPLOAD, ChangeType.DELETE);
  }

  @Test
  @DisplayName("a folder of more rows than one statement can bind deletes, and frees what it held")
  void deletingAHugeFolder() throws Exception {
    createFolder("", "big");
    jdbc.update(
        "INSERT INTO file_metadata (path, filename, size, is_directory, current_version, user_id,"
            + " created_at, updated_at) SELECT 'big/f' || n, 'f' || n, 1, false, 1, ?, now(), now()"
            + " FROM generate_series(1, 70000) AS n",
        owner.getId());
    jdbc.update("UPDATE users SET used_bytes = used_bytes + 70000 WHERE id = ?", owner.getId());

    deletePath("big");

    assertThat(metadata.findAll()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT used_bytes FROM users WHERE id = ?", Long.class, owner.getId()))
        .isZero();
  }

  @Test
  @DisplayName("deleting a folder removes the rows of everything inside it")
  void deleteFolderRemovesChildRows() throws Exception {
    createFolder("", "docs");
    createFolder("docs", "nested");
    upload("docs/nested", "deep.txt", "x");
    createFolder("", "docs_other");

    deletePath("docs");

    assertThat(metadata.findAll()).extracting(FileMetadata::getPath).containsExactly("docs_other");
  }

  @Test
  @DisplayName("deleting a folder leaves folders that only match it as a LIKE pattern alone")
  void deleteFolderTreatsWildcardsLiterally() throws Exception {
    createFolder("", "a_");
    createFolder("", "ab");
    upload("ab", "keep.txt", "x");
    upload("ab", "keep.txt", "y");

    deletePath("a_");

    assertThat(metadata.findAll())
        .extracting(FileMetadata::getPath)
        .containsExactlyInAnyOrder("ab", "ab/keep.txt");
    assertThat(versionFiles()).hasSize(1);
  }

  // --- versions and restore ---------------------------------------------------

  @Test
  @DisplayName("restoring the same version as a copy twice creates two distinct copies")
  void restoreAsCopyTwice() throws Exception {
    upload("", "notes.txt", "first");
    upload("", "notes.txt", "second");
    long id = idOf("notes.txt");

    restore(id, 1, "COPY");
    restore(id, 1, "COPY");

    assertThat(home.resolve("notes_v1.txt")).hasContent("first");
    assertThat(home.resolve("notes_v1 (2).txt")).hasContent("first");
    assertThat(home.resolve("notes.txt")).hasContent("second");
    mockMvc.perform(get("/api/files")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("restoring in place keeps the replaced content as a new version")
  void restoreInPlaceKeepsCurrentContent() throws Exception {
    upload("", "plan.txt", "draft");
    upload("", "plan.txt", "final");
    long id = idOf("plan.txt");

    restore(id, 1, "OVERWRITE");

    assertThat(home.resolve("plan.txt")).hasContent("draft");
    restore(id, 2, "OVERWRITE");
    assertThat(home.resolve("plan.txt")).hasContent("final");
    assertThat(history.findAll())
        .filteredOn(h -> h.getChangeType() == ChangeType.RESTORE)
        .extracting(FileHistory::getDetails, FileHistory::getErrorMessage)
        .contains(Tuple.tuple("Restored version 1", null));
  }

  @Test
  @DisplayName("files with the same name in different folders keep separate versions")
  void sameNameInDifferentFolders() throws Exception {
    createFolder("", "a");
    createFolder("", "b");
    upload("a", "report.txt", "a1");
    upload("b", "report.txt", "b1");
    upload("a", "report.txt", "a2");
    upload("b", "report.txt", "b2");

    restore(idOf("a/report.txt"), 1, "OVERWRITE");
    restore(idOf("b/report.txt"), 1, "OVERWRITE");

    assertThat(home.resolve("a/report.txt")).hasContent("a1");
    assertThat(home.resolve("b/report.txt")).hasContent("b1");
  }

  @Test
  @DisplayName("uploaded and restored files get the permissions of any new file")
  void uploadedAndRestoredFilesHaveDefaultPermissions() throws Exception {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    Set<PosixFilePermission> ordinary =
        Files.getPosixFilePermissions(Files.createFile(home.resolve("ordinary.txt")));

    upload("", "report.txt", "one");
    upload("", "report.txt", "two");
    assertThat(Files.getPosixFilePermissions(home.resolve("report.txt"))).isEqualTo(ordinary);
    restore(idOf("report.txt"), 1, "OVERWRITE");
    assertThat(Files.getPosixFilePermissions(home.resolve("report.txt"))).isEqualTo(ordinary);
    restore(idOf("report.txt"), 1, "COPY");
    assertThat(Files.getPosixFilePermissions(home.resolve("report_v1.txt"))).isEqualTo(ordinary);
  }

  @Test
  @DisplayName("versions are listed newest first, with their size in bytes and author")
  void versionsAreListed() throws Exception {
    upload("", "list.txt", "a");
    upload("", "list.txt", "bb");
    upload("", "list.txt", "ccc");

    mockMvc
        .perform(get("/api/files/" + idOf("list.txt") + "/versions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].version").value(2))
        .andExpect(jsonPath("$[0].size").value(2))
        .andExpect(jsonPath("$[0].createdBy").value("owner"))
        .andExpect(jsonPath("$[1].version").value(1))
        .andExpect(jsonPath("$[1].size").value(1));
  }

  @Test
  @DisplayName("the oldest versions past the limit are pruned from the database and the disk")
  void oldVersionsArePruned() throws Exception {
    for (String content : List.of("1", "2", "3", "4")) {
      upload("", "busy.txt", content);
    }

    // Each upload after the first kept what it replaced: "1" as v1, "2" as v2 and "3" as v3.
    assertThat(versions.findAll())
        .extracting(FileVersion::getVersion)
        .containsExactlyInAnyOrder(2, 3);
    assertThat(versionFiles()).extracting(Files::readString).containsExactlyInAnyOrder("2", "3");
  }

  @Test
  @DisplayName("replacing a file that was copied in by hand keeps its content as a version")
  void untrackedFileIsVersionedBeforeReplace() throws Exception {
    Files.writeString(home.resolve("manual.txt"), "by hand");

    upload("", "manual.txt", "uploaded");
    restore(idOf("manual.txt"), 1, "COPY");

    assertThat(home.resolve("manual_v1.txt")).hasContent("by hand");
  }

  @Test
  @DisplayName(
      "an upload onto a file removed from the disk by hand becomes its content, versions kept")
  void uploadOntoAFileGoneFromTheDiskKeepsIt() throws Exception {
    upload("", "ghost.txt", "old one");
    upload("", "ghost.txt", "old two");
    long id = idOf("ghost.txt");
    Files.delete(home.resolve("ghost.txt"));

    upload("", "ghost.txt", "new");

    assertThat(metadata.findAll()).singleElement().extracting(FileMetadata::getId).isEqualTo(id);
    assertThat(versions.findAll()).hasSize(1);
    assertThat(home.resolve("ghost.txt")).hasContent("new");
  }

  @Test
  @DisplayName("a copy restore leaves a file copied in by hand under its name alone")
  void copyRestoreSkipsAnUnlistedFile() throws Exception {
    upload("", "n.txt", "one");
    upload("", "n.txt", "two");
    Files.writeString(home.resolve("n_v1.txt"), "copied in by hand");

    restore(idOf("n.txt"), 1, "COPY");

    assertThat(home.resolve("n_v1.txt")).hasContent("copied in by hand");
    assertThat(home.resolve("n_v1 (2).txt")).hasContent("one");
    assertThat(metadata.findPathsOf(owner.getId()))
        .containsExactlyInAnyOrder("n.txt", "n_v1 (2).txt");
  }

  @Test
  @DisplayName("restoring a file where a folder was put by hand is refused, and the folder kept")
  void restoreOverAFolderPutByHandIsRefused() throws Exception {
    upload("", "r.txt", "one");
    upload("", "r.txt", "two");
    long id = idOf("r.txt");
    Files.delete(home.resolve("r.txt"));
    Files.createDirectories(home.resolve("r.txt"));
    Files.writeString(home.resolve("r.txt/inside.txt"), "x");

    mockMvc
        .perform(
            post("/api/files/" + id + "/versions/1/restore")
                .param("mode", "OVERWRITE")
                .with(csrf()))
        .andExpect(status().isConflict());

    assertThat(home.resolve("r.txt/inside.txt")).hasContent("x");
    assertThat(versions.findAll()).hasSize(1);
  }

  @Test
  @DisplayName("a copy restore doesn't take a name that is listed, even if it's gone from the disk")
  void copyRestoreSkipsListedNames() throws Exception {
    upload("", "n.txt", "one");
    upload("", "n.txt", "two");
    upload("", "n_v1.txt", "listed");
    Files.delete(home.resolve("n_v1.txt"));

    restore(idOf("n.txt"), 1, "COPY");

    assertThat(home.resolve("n_v1 (2).txt")).hasContent("one");
  }

  @Test
  @DisplayName("dropping a file's versions removes its version folder even if it holds strays")
  void discardedVersionFolderIsRemovedWithStrays() throws Exception {
    upload("", "ghost.txt", "old one");
    upload("", "ghost.txt", "old two");
    Path folder = servingDir.resolve(".versions/" + idOf("ghost.txt"));
    Files.writeString(folder.resolve("stray"), "left over");

    deletePath("ghost.txt");

    assertThat(folder).doesNotExist();
  }

  // --- clean-up at startup ---------------------------------------------------------

  @Test
  @DisplayName("stale scratch files and stale unreferenced versions of known files are removed")
  void startupSweepRemovesLeftovers() throws Exception {
    createFolder("", "docs");
    upload("", "kept.txt", "one");
    upload("", "kept.txt", "two");
    Path versionFolder = servingDir.resolve(".versions/" + idOf("kept.txt"));
    Path staleScratch = old(Files.writeString(home.resolve("docs/.upload-1.tmp"), "x"));
    Path freshScratch = Files.writeString(home.resolve(".upload-2.tmp"), "x");
    Path referenced = old(versionFolder.resolve("v1"));
    Path unreferenced = old(Files.writeString(versionFolder.resolve("v7"), "x"));
    Path freshUnreferenced = Files.writeString(versionFolder.resolve("v8"), "x");
    Path emptyUnknownFolder = old(Files.createDirectories(servingDir.resolve(".versions/777777")));

    sweeper.sweep();

    assertThat(staleScratch).doesNotExist();
    assertThat(freshScratch).exists();
    assertThat(referenced).hasContent("one");
    assertThat(unreferenced).doesNotExist();
    assertThat(freshUnreferenced).exists();
    assertThat(emptyUnknownFolder).doesNotExist();
  }

  @Test
  @DisplayName("stale uploads and temporary files left on the local disk are removed")
  void startupSweepRemovesLocalLeftovers() throws Exception {
    Path uploads = Files.createDirectories(storagePaths.uploadsDir());
    Path temp = Files.createDirectories(storagePaths.tempDir());
    Path staleUpload = old(Files.writeString(uploads.resolve("upload_a_00000001.tmp"), "x"));
    Path freshUpload = Files.writeString(uploads.resolve("upload_b_00000002.tmp"), "x");
    Path stalePart = old(Files.writeString(temp.resolve("javadropbox-1.part"), "x"));
    Path freshPart = Files.writeString(temp.resolve("javadropbox-2.part"), "x");
    Path staleScratch = old(Files.writeString(temp.resolve("PDFBox123.tmp"), "x"));
    Path freshScratch = Files.writeString(temp.resolve("PDFBox456.tmp"), "x");
    Path notOurs = old(Files.writeString(uploads.resolve("notes.txt"), "x"));

    sweeper.sweep();

    assertThat(staleUpload).doesNotExist();
    assertThat(freshUpload).exists();
    assertThat(stalePart).doesNotExist();
    assertThat(freshPart).exists();
    assertThat(staleScratch).doesNotExist();
    assertThat(freshScratch).exists();
    assertThat(notOurs).exists();
  }

  @Test
  @DisplayName(
      "the sweep leaves alone the versions in a folder whose file the database doesn't know")
  void startupSweepKeepsFoldersOfUnknownFiles() throws Exception {
    upload("", "kept.txt", "one");
    Path unknownFolder = old(Files.createDirectories(servingDir.resolve(".versions/999999")));
    Path version = old(Files.writeString(unknownFolder.resolve("v1"), "precious"));
    old(unknownFolder);
    Path legacy = old(Files.writeString(servingDir.resolve(".versions/report.txt.v1"), "older"));

    sweeper.sweep();

    assertThat(version).hasContent("precious");
    assertThat(legacy).hasContent("older");
  }

  @Test
  @DisplayName("the sweep removes an old blob scratch file and leaves the blobs alone")
  void startupSweepRemovesBlobScratchButNotBlobs() throws Exception {
    Path blobs = Files.createDirectories(servingDir.resolve(".blobs/ab"));
    Path blob = old(Files.writeString(blobs.resolve("ab" + "0".repeat(62)), "a blob"));
    Path scratch = old(Files.writeString(servingDir.resolve(".blobs/.upload-x.tmp"), "left"));
    // Shaped like a scratch file, but below the blob folders, where the sweep doesn't look.
    Path deep = old(Files.writeString(blobs.resolve(".upload-y.tmp"), "kept"));

    sweeper.sweep();

    assertThat(blob).hasContent("a blob");
    assertThat(scratch).doesNotExist();
    assertThat(deep).exists();
  }

  @Test
  @DisplayName("against an empty database the sweep deletes no version at all")
  void startupSweepSkipsVersionsWhenTheDatabaseIsEmpty() throws Exception {
    upload("", "kept.txt", "one");
    upload("", "kept.txt", "two");
    Path version = old(servingDir.resolve(".versions/" + idOf("kept.txt") + "/v1"));
    old(version.getParent());
    // E.g. a new database volume, or a wrong datasource URL, with the existing data folder.
    TestDatabase.wipe(jdbc);

    sweeper.sweep();

    assertThat(version).hasContent("one");
  }

  @Test
  @DisplayName("a stored copy left over from an interrupted operation does not block a replace")
  void leftoverVersionFileIsReplaced() throws Exception {
    upload("", "stale.txt", "one");
    Path leftover = servingDir.resolve(".versions/" + idOf("stale.txt") + "/v1");
    Files.createDirectories(leftover.getParent());
    Files.writeString(leftover, "from a crash");

    upload("", "stale.txt", "two");

    assertThat(leftover).hasContent("one");
    assertThat(home.resolve("stale.txt")).hasContent("two");
  }

  // --- attribution ---------------------------------------------------------------

  @Test
  @DisplayName("changes are stored in and attributed to the signed-in user, not the first account")
  void changesAreAttributedToTheSignedInUser() throws Exception {
    users.deleteAllInBatch();
    User first = users.save(new User("first", "unused", "ROLE_ADMIN"));
    User owner = users.save(new User("owner", "unused", "ROLE_USER"));

    upload("", "mine.txt", "x");

    assertThat(localStore.path(storagePaths.home(owner).key()).resolve("mine.txt")).hasContent("x");
    assertThat(localStore.path(storagePaths.home(first).key()).resolve("mine.txt")).doesNotExist();
    assertThat(metadata.findByPath(owner.getId(), "mine.txt").orElseThrow().getOwner().getId())
        .isEqualTo(owner.getId());
    assertThat(history.findAll())
        .extracting(h -> h.getUser().getId())
        .containsExactly(owner.getId());
  }

  // --- history -------------------------------------------------------------------

  @Test
  @DisplayName("the history is paged, newest first, with the page size capped")
  void historyIsPaged() throws Exception {
    createFolder("", "one");
    createFolder("", "two");
    createFolder("", "three");

    mockMvc
        .perform(get("/api/history").param("page", "0").param("size", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].filePath").value("three"))
        .andExpect(jsonPath("$.totalItems").value(3))
        .andExpect(jsonPath("$.totalPages").value(2));
    mockMvc
        .perform(get("/api/history").param("page", "1").param("size", "2"))
        .andExpect(jsonPath("$.items[0].filePath").value("one"));
    mockMvc
        .perform(get("/api/history").param("size", "100000"))
        .andExpect(jsonPath("$.size").value(200));
    mockMvc.perform(get("/api/history").param("page", "-1")).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("a history page too far out to address is a 400, not a 500")
  void historyPageBeyondReachIsRefused() throws Exception {
    mockMvc
        .perform(get("/api/history").param("page", "100000000").param("size", "200"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/history").param("page", "10000000").param("size", "200"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
  }

  // --- failures ----------------------------------------------------------------

  @Test
  @DisplayName("an upload that fails part-way leaves the previous content in place")
  void failedUploadKeepsPreviousContent() throws Exception {
    upload("", "precious.txt", "original");

    MultipartFile broken = new BrokenUpload("precious.txt");
    // MockMvc cleared the security context when its request ended; sign in again to call directly.
    SecurityContextHolder.setContext(TestSecurityContextHolder.getContext());
    assertThatThrownBy(() -> fileService.upload(new MultipartFile[] {broken}, ""))
        .isInstanceOf(IOException.class);

    assertThat(home.resolve("precious.txt")).hasContent("original");
    assertThat(versions.findAll()).isEmpty();
    try (Stream<Path> leftovers = Files.list(home)) {
      assertThat(leftovers.map(p -> p.getFileName().toString())).containsExactly("precious.txt");
    }
  }

  @Test
  @DisplayName("a failed operation is recorded in the history even though it rolled back")
  void failureIsRecorded() throws Exception {
    createFolder("", "dup");

    mockMvc
        .perform(
            post("/api/folders")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("path", "")
                .param("name", "dup")
                .with(csrf()))
        .andExpect(status().isConflict());

    assertThat(history.findAll())
        .filteredOn(h -> !h.isSuccess())
        .extracting(
            FileHistory::getChangeType, FileHistory::getFilePath, FileHistory::getErrorMessage)
        .containsExactly(
            Tuple.tuple(ChangeType.CREATE_FOLDER, "dup", "\"dup\" already exists here"));
  }

  @Test
  @DisplayName("a failure recorded in the history never reveals server paths or exception types")
  void recordedFailureHidesServerDetails() throws Exception {
    createFolder("", "locked");
    Path locked = home.resolve("locked");
    assertThat(locked.toFile().setWritable(false)).isTrue();
    try {
      assumeFalse(Files.isWritable(locked), "running as root, which can write anyway");
      mockMvc
          .perform(
              multipart("/api/files")
                  .file(new MockMultipartFile("files", "a.txt", "text/plain", "x".getBytes()))
                  .param("path", "locked")
                  .with(csrf().asHeader()))
          .andExpect(status().isInternalServerError());
    } finally {
      locked.toFile().setWritable(true);
    }

    assertThat(history.findAll())
        .filteredOn(h -> !h.isSuccess())
        .singleElement()
        .extracting(FileHistory::getErrorMessage)
        .isEqualTo("The operation could not be completed.");
  }

  @Test
  @DisplayName("an over-long name is refused before it reaches the disk or the database")
  void overLongNameIsRefused() throws Exception {
    String name = "x".repeat(256);

    mockMvc
        .perform(
            post("/api/folders")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("path", "")
                .param("name", name)
                .with(csrf()))
        .andExpect(status().isBadRequest());

    assertThat(home.resolve("x".repeat(255))).doesNotExist();
    assertThat(history.findAll()).filteredOn(h -> !h.isSuccess()).hasSize(1);
  }

  @Test
  @DisplayName("an over-long failure message is truncated so the failure can still be saved")
  void longFailureMessageIsTruncated() {
    FileHistory saved =
        history.save(FileHistory.failure("p", "p", ChangeType.UPLOAD, null, "e".repeat(5000)));

    assertThat(history.findById(saved.getId()).orElseThrow().getErrorMessage()).hasSize(1024);
  }

  @Test
  @DisplayName("deleting something that does not exist is a 404, not a 500")
  void deleteMissingIsNotFound() throws Exception {
    mockMvc
        .perform(delete("/api/files").param("path", "nope.txt").with(csrf()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("restoring a version that does not exist is a 404")
  void restoreMissingVersionIsNotFound() throws Exception {
    upload("", "one.txt", "only");

    mockMvc
        .perform(post("/api/files/" + idOf("one.txt") + "/versions/7/restore").with(csrf()))
        .andExpect(status().isNotFound());
  }

  // --- letter case -------------------------------------------------------------

  @Test
  @DisplayName("an upload spelled in a different letter case replaces the file under its one row")
  void caseVariantUploadKeepsOneRow() throws Exception {
    FileSystemAssumptions.assumeCaseInsensitive(servingDir);
    upload("", "report.txt", "one");
    upload("", "report.txt", "two");
    upload("", "Report.txt", "three");

    assertThat(metadata.findAll()).extracting(FileMetadata::getPath).containsExactly("report.txt");
    assertThat(versions.findAll()).hasSize(2);
    assertThat(home.resolve("report.txt")).hasContent("three");
  }

  @Test
  @DisplayName("deleting a file spelled in a different letter case removes its row")
  void caseVariantDeleteRemovesTheRow() throws Exception {
    FileSystemAssumptions.assumeCaseInsensitive(servingDir);
    upload("", "notes.txt", "a");
    upload("", "notes.txt", "b");

    deletePath("NOTES.TXT");

    assertThat(home.resolve("notes.txt")).doesNotExist();
    assertThat(metadata.findAll()).isEmpty();
    assertThat(versions.findAll()).isEmpty();
  }

  // --- helpers ------------------------------------------------------------------

  private void upload(String folder, String name, String content) throws Exception {
    MockMultipartFile file =
        new MockMultipartFile(
            "files", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    mockMvc
        .perform(multipart("/api/files").file(file).param("path", folder).with(csrf().asHeader()))
        .andExpect(status().isOk());
  }

  private void createFolder(String parent, String name) throws Exception {
    mockMvc
        .perform(
            post("/api/folders")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("path", parent)
                .param("name", name)
                .with(csrf()))
        .andExpect(status().isOk());
  }

  private void deletePath(String path) throws Exception {
    mockMvc
        .perform(delete("/api/files").param("path", path).with(csrf()))
        .andExpect(status().isOk());
  }

  private void restore(long id, int version, String mode) throws Exception {
    mockMvc
        .perform(
            post("/api/files/" + id + "/versions/" + version + "/restore")
                .param("mode", mode)
                .with(csrf()))
        .andExpect(status().isOk());
  }

  private static void deleteFolderByHand(Path folder) throws IOException {
    try (Stream<Path> walk = Files.walk(folder)) {
      for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }

  private static String sha256(String content) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
  }

  // Edited a little after the app wrote it, as by hand. Set outright, since a filesystem stamps
  // times from a clock coarse enough that an edit made at once can look older than the app's write.
  private static Path later(Path path) throws IOException {
    Files.setLastModifiedTime(path, FileTime.from(Instant.now().plusSeconds(5)));
    return path;
  }

  // Last modified two hours ago, i.e. not something a request could still be writing.
  private static Path old(Path path) throws IOException {
    Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
    return path;
  }

  private long idOf(String path) {
    return metadata.findByPath(owner.getId(), path).orElseThrow().getId();
  }

  private List<Path> versionFiles() throws IOException {
    Path store = servingDir.resolve(".versions");
    if (!Files.exists(store)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(store)) {
      return walk.filter(Files::isRegularFile).toList();
    }
  }

  /** An upload whose stream dies after a few bytes, like a dropped connection. */
  private static final class BrokenUpload extends MockMultipartFile {

    BrokenUpload(String name) {
      super("files", name, "text/plain", "partial content that never finishes".getBytes());
    }

    @Override
    public InputStream getInputStream() throws IOException {
      return new FilterInputStream(super.getInputStream()) {
        private int read;

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
          if (read > 4) {
            throw new IOException("connection reset");
          }
          int n = super.read(b, off, Math.min(len, 5));
          read += Math.max(n, 0);
          return n;
        }
      };
    }
  }
}
