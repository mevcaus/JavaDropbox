package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The "Forgot your password?" procedure in docs/self-hosting.md. It has to work with the real
 * foreign keys: deleting the account instead, as the README once said, fails as soon as the account
 * has uploaded anything.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Password recovery (documented procedure, on PostgreSQL)")
class PasswordRecoveryIntegrationTests {

  @TempDir static Path servingDir;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
  }

  // htpasswd -bnBC 10 "" new-password-123 | tr -d ':\n'. htpasswd writes the $2y$ variant,
  // which Spring's BCryptPasswordEncoder must accept for the documented procedure to work.
  private static final String NEW_PASSWORD_HASH =
      "$2y$10$wa9JRAmJeEMyspLfpc1fjevaIaEg3Rem/sv/rkjxhVsUEl1kz0mfS";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() throws Exception {
    users.save(new User("owner", passwordEncoder.encode("forgotten-password"), "ROLE_ADMIN"));
    upload("notes.txt", "first");
    upload("notes.txt", "second");
  }

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  @Test
  @DisplayName("setting a new bcrypt hash with UPDATE signs in with the new password, files kept")
  void updatingThePasswordHashRecoversTheAccount() throws Exception {
    Long ownerId = users.findByUsername("owner").orElseThrow().getId();

    // The documented statement, with a hash from its htpasswd command.
    jdbc.update("UPDATE users SET password = ? WHERE username = 'owner'", NEW_PASSWORD_HASH);

    login("owner", "new-password-123").andExpect(status().isOk());
    login("owner", "forgotten-password").andExpect(status().isUnauthorized());
    assertThat(users.findByUsername("owner").orElseThrow().getId()).isEqualTo(ownerId);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM file_versions", Integer.class))
        .isEqualTo(1);
  }

  private ResultActions login(String username, String password) throws Exception {
    return mockMvc.perform(
        post("/login").with(csrf()).param("username", username).param("password", password));
  }

  private void upload(String name, String content) throws Exception {
    MockMultipartFile file =
        new MockMultipartFile(
            "files", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    mockMvc
        .perform(
            multipart("/api/files")
                .file(file)
                .param("path", "")
                .with(user("owner"))
                .with(csrf().asHeader()))
        .andExpect(status().isOk());
  }
}
