package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "testuser")
@DisplayName("Storage path security")
class StoragePathSecurityTests {

  @TempDir static Path servingDir;
  @TempDir static Path outsideDir;

  @DynamicPropertySource
  static void overrideServingDirectory(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
  }

  @Autowired private MockMvc mockMvc;

  @Autowired private UserRepository userRepository;

  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

  @org.junit.jupiter.api.AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  @BeforeEach
  void setUp() throws IOException {
    if (userRepository.count() == 0) {
      userRepository.save(new User("testuser", "unused", "ROLE_ADMIN"));
    }
    Files.writeString(servingDir.resolve("keep.txt"), "keep");
    Files.writeString(outsideDir.resolve("secret.txt"), "secret");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ".", "./", "folder/.."})
  @DisplayName("deleting the root folder, under any spelling, is refused")
  void deletingRootIsRefused(String path) throws Exception {
    mockMvc
        .perform(delete("/api/files").param("path", path).with(csrf()))
        .andExpect(status().isBadRequest());

    assertThat(servingDir).isDirectory();
    assertThat(servingDir.resolve("keep.txt")).exists();
  }

  @ParameterizedTest
  @ValueSource(strings = {"../secret.txt", "a/../../secret.txt", "/etc/passwd"})
  @DisplayName("paths outside the serving directory are refused")
  void traversalIsRefused(String path) throws Exception {
    mockMvc
        .perform(get("/api/files/download").param("path", path))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("the version store cannot be downloaded, deleted or shared")
  void versionStoreIsUnreachable() throws Exception {
    Files.createDirectories(servingDir.resolve(".versions"));
    Files.writeString(servingDir.resolve(".versions/keep.txt.v1"), "old");

    mockMvc
        .perform(get("/api/files/download").param("path", ".versions/keep.txt.v1"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(delete("/api/files").param("path", ".versions").with(csrf()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(post("/api/share").param("path", ".versions/keep.txt.v1").with(csrf()))
        .andExpect(status().isBadRequest());

    assertThat(servingDir.resolve(".versions/keep.txt.v1")).exists();
  }

  @Test
  @DisplayName("the app's internal directory, which holds the share-link key, is unreachable")
  void internalDirectoryIsUnreachable() throws Exception {
    Files.createDirectories(servingDir.resolve(".javadropbox"));
    Files.writeString(servingDir.resolve(".javadropbox/share-jwt.key"), "key");

    mockMvc
        .perform(get("/api/files/download").param("path", ".javadropbox/share-jwt.key"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("the root folder cannot be shared")
  void rootCannotBeShared() throws Exception {
    mockMvc
        .perform(post("/api/share").param("path", "").with(csrf()))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("a symlink cannot be used to reach files outside the serving directory")
  void symlinkEscapeIsRefused() throws Exception {
    Path link = servingDir.resolve("escape");
    Files.deleteIfExists(link);
    Files.createSymbolicLink(link, outsideDir);

    mockMvc
        .perform(get("/api/files/download").param("path", "escape/secret.txt"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/files"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("escape"))));
  }

  @Test
  @DisplayName("a symlink loop does not break the file tree")
  void symlinkLoopIsSkipped() throws Exception {
    Path link = servingDir.resolve("loop");
    Files.deleteIfExists(link);
    Files.createSymbolicLink(link, servingDir);

    mockMvc.perform(get("/api/files")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("a filename that merely contains two dots is accepted")
  void doubleDotInsideNameIsAccepted() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("files", "notes..v2.txt", "text/plain", "hello".getBytes());

    mockMvc
        .perform(multipart("/api/files").file(file).param("path", "").with(csrf()))
        .andExpect(status().isOk());

    assertThat(servingDir.resolve("notes..v2.txt")).hasContent("hello");
  }

  @ParameterizedTest
  @ValueSource(strings = {"..", "a/b.txt"})
  @DisplayName("an uploaded filename that is not a single path segment is refused")
  void uploadNameMustBeOneSegment(String name) throws Exception {
    MockMultipartFile file = new MockMultipartFile("files", name, "text/plain", "x".getBytes());

    mockMvc
        .perform(multipart("/api/files").file(file).param("path", "").with(csrf()))
        .andExpect(status().isBadRequest());
  }
}
