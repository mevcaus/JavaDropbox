package com.javadropbox.javadropbox;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Endpoints that take a form or multipart body refuse anything else, so a password or setup code
 * can't come in through the query string, where it would end up in access logs and history.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "formuser")
@TempServingDirectory
@DisplayName("Form and multipart requests")
class FormContentTypeIntegrationTests {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void account() {
    users
        .findByUsername("formuser")
        .orElseGet(() -> users.save(new User("formuser", "unused", "ROLE_ADMIN")));
  }

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  @Test
  @DisplayName("refuse a password in the query string with a 415")
  void refuseQueryStringPasswords() throws Exception {
    mockMvc
        .perform(
            post("/api/reset-password/some-token")
                .param("password", "secret-password")
                .with(csrf()))
        .andExpect(status().isUnsupportedMediaType());
    mockMvc
        .perform(post("/api/invite/some-token").param("password", "secret-password").with(csrf()))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  @DisplayName("refuse a share link asked for without a form")
  void refuseShareWithoutForm() throws Exception {
    mockMvc
        .perform(post("/api/share").param("path", "a.txt").with(csrf()))
        .andExpect(status().isUnsupportedMediaType());
  }

  // The app sends a new folder as multipart, scripts and the e2e tests as a form.
  @Test
  @DisplayName("take a new folder as multipart or as a form")
  void takeFoldersBothWays() throws Exception {
    mockMvc
        .perform(
            multipart("/api/folders")
                .param("path", "")
                .param("name", "Sent as multipart")
                // A multipart body isn't read before the CSRF check, so the token goes in a header,
                // as the app sends it.
                .with(csrf().asHeader()))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/folders")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("path", "")
                .param("name", "Sent as a form")
                .with(csrf()))
        .andExpect(status().isOk());
  }
}
