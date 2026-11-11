package com.javadropbox.javadropbox;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Endpoints that take a form or multipart body refuse anything else, and no request that changes
 * something takes a password or setup code from its URL, where it would end up in access logs,
 * proxies and browser history.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "formuser", roles = "ADMIN")
@TempServingDirectory
@DisplayName("Form and multipart requests")
class FormContentTypeIntegrationTests {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private JdbcTemplate jdbc;

  private User account;

  @BeforeEach
  void account() {
    account =
        users
            .findByUsername("formuser")
            .orElseGet(() -> users.save(new User("formuser", "unused", "ROLE_ADMIN")));
  }

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  // Each endpoint sent a body of a type it doesn't take: JSON to a form, a form to an upload.
  @ParameterizedTest(name = "{0} {1} as {2}")
  @CsvSource({
    "POST, /api/admin/invites, application/json",
    "PUT, /api/admin/users/{id}/enabled, application/json",
    "PUT, /api/admin/users/{id}/role, application/json",
    "PUT, /api/admin/users/{id}/quota, application/json",
    "POST, /api/invite/some-token, application/json",
    "POST, /api/reset-password/some-token, application/json",
    "POST, /api/share, multipart/form-data",
    "POST, /api/files, application/x-www-form-urlencoded",
    "POST, /api/folders, application/json",
  })
  @DisplayName("refuse a body of another type with a 415 that says which to send")
  void refuseOtherTypes(String method, String path, String type) throws Exception {
    mockMvc
        .perform(
            request(HttpMethod.valueOf(method), path.replace("{id}", account.getId().toString()))
                .contentType(type)
                .content("{}")
                .with(csrf().asHeader()))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(header().exists("Accept"))
        .andExpect(jsonPath("$.message", containsString("Send the request body as")));
  }

  // Spring would merge the query string into the form, so the content type alone isn't enough.
  @Test
  @DisplayName("refuse a password in the URL, even with a form content type")
  void refusePasswordsInTheUrl() throws Exception {
    mockMvc
        .perform(
            post("/api/invite/some-token?password=query-password-123")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .with(csrf()))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.message").value("Send the password in the request body, not in the URL."));
    mockMvc
        .perform(
            post("/api/reset-password/some-token?password=query-password-123")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .with(csrf()))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("refuse a sign-in or setup code in the URL")
  void refuseSignInAndSetupInTheUrl() throws Exception {
    mockMvc
        .perform(post("/login?username=formuser&password=query-password-123").with(csrf()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/setup?code=ABCDE-FGHIJ")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .with(csrf()))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.message").value("Send the code in the request body, not in the URL."));
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
