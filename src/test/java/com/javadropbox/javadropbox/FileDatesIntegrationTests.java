package com.javadropbox.javadropbox;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.setup.required=false", "app.setup.filter.enabled=true"})
@DisplayName("File Dates Integration Tests")
class FileDatesIntegrationTests {

  private static final String ISO_INSTANT_PATTERN =
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$";

  @TempDir static Path servingDir;

  @DynamicPropertySource
  static void overrideServingDirectory(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
  }

  @Autowired private MockMvc mockMvc;

  @Autowired private UserRepository userRepository;

  @Autowired private PasswordEncoder passwordEncoder;

  @BeforeEach
  void setUp() throws IOException {
    if (userRepository.count() == 0) {
      userRepository.save(new User("testadmin", passwordEncoder.encode("password"), "ROLE_ADMIN"));
    }
    Files.writeString(servingDir.resolve("testfile.txt"), "hello world");
  }

  @AfterEach
  void tearDown() {
    userRepository.deleteAll();
  }

  @Test
  @DisplayName("Files API returns ISO-8601 timestamps")
  @WithMockUser(
      username = "testuser",
      roles = {"USER"})
  void filesApiReturnsIso8601Timestamps() throws Exception {
    mockMvc
        .perform(get("/api/files"))
        .andExpect(status().isOk())
        // Timestamps must be absolute instants, e.g. "2026-09-12T16:00:00.123456789Z". The
        // trailing Z is the part that matters: without an offset a client cannot render the
        // value in the viewer's own timezone, which is the whole point of not formatting
        // server-side. The fractional seconds are optional because ISO_INSTANT omits them
        // when the instant lands exactly on a second.
        .andExpect(jsonPath("$[0].createdDate", matchesPattern(ISO_INSTANT_PATTERN)))
        .andExpect(jsonPath("$[0].lastModified", matchesPattern(ISO_INSTANT_PATTERN)));
  }
}
