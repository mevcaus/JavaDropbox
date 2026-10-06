package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.StorageQuota;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
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

@SpringBootTest(properties = "javadropbox.storage.max-total-size=1KB")
@AutoConfigureMockMvc
@WithMockUser(username = "owner")
@DisplayName("Storage cap")
class StorageQuotaIntegrationTests {

  @TempDir static Path servingDir;

  @DynamicPropertySource
  static void overrideServingDirectory(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private FileMetadataRepository metadata;
  @Autowired private StorageQuota quota;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    users.save(new User("owner", "unused", "ROLE_ADMIN"));
  }

  @AfterEach
  void tearDown() throws IOException {
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

  @Test
  @DisplayName("an upload that would go over the cap is a 507 and leaves nothing behind")
  void uploadOverTheCapIsRefused() throws Exception {
    upload("a.txt", 600).andExpect(status().isOk());

    upload("b.txt", 600)
        .andExpect(status().isInsufficientStorage())
        .andExpect(jsonPath("$.message").value(Matchers.containsString("at most 1 KB")));

    assertThat(servingDir.resolve("b.txt")).doesNotExist();
    assertThat(metadata.findByPath("b.txt")).isEmpty();
    assertThat(quota.usedBytes()).isEqualTo(600);
  }

  @Test
  @DisplayName("previous versions count toward the cap")
  void versionsCount() throws Exception {
    upload("a.txt", 400).andExpect(status().isOk());
    upload("a.txt", 400).andExpect(status().isOk());

    upload("a.txt", 400).andExpect(status().isInsufficientStorage());

    assertThat(quota.usedBytes()).isEqualTo(800);
    assertThat(servingDir.resolve("a.txt")).hasSize(400);
  }

  @Test
  @DisplayName("restoring a version as a copy is refused when the copy would not fit")
  void restoreOverTheCapIsRefused() throws Exception {
    upload("a.txt", 400).andExpect(status().isOk());
    upload("a.txt", 300).andExpect(status().isOk());
    long id = metadata.findByPath("a.txt").orElseThrow().getId();

    mockMvc
        .perform(
            post("/api/files/" + id + "/versions/1/restore").param("mode", "COPY").with(csrf()))
        .andExpect(status().isInsufficientStorage());

    assertThat(servingDir.resolve("a_v1.txt")).doesNotExist();
    assertThat(quota.usedBytes()).isEqualTo(700);
  }

  private ResultActions upload(String name, int size) throws Exception {
    MockMultipartFile file = new MockMultipartFile("files", name, "text/plain", new byte[size]);
    return mockMvc.perform(
        multipart("/api/files").file(file).param("path", "").with(csrf().asHeader()));
  }
}
