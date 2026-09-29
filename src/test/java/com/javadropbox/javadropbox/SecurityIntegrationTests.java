package com.javadropbox.javadropbox;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileHistoryRepository;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.AuthService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Security Integration Tests - Normal Operation")
class SecurityIntegrationTests {

  @Autowired private MockMvc mockMvc;

  @Autowired private AuthService authService;

  @Autowired private UserRepository userRepository;

  @Autowired private FileHistoryRepository fileHistoryRepository;

  @Autowired private FileMetadataRepository fileMetadataRepository;

  @Autowired private PasswordEncoder passwordEncoder;

  @BeforeEach
  void setUp() {
    // Ensure setup is NOT required by creating a user
    if (userRepository.count() == 0) {
      User user = new User("testadmin", passwordEncoder.encode("password"), "ROLE_ADMIN");
      userRepository.save(user);
    }
  }

  @Autowired private JdbcTemplate jdbc;

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  // ------------------------------
  // Basic Authentication Tests
  // ------------------------------

  @Test
  @DisplayName("Unauthenticated user should receive 401 when accessing protected API")
  void unauthenticatedUserReceives401() throws Exception {
    mockMvc.perform(get("/api/files")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Authenticated user can access API")
  @WithMockUser(
      username = "testuser",
      roles = {"USER"})
  void authenticatedUserCanAccessApi() throws Exception {
    mockMvc
        .perform(get("/api/files"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON));
  }

  @Test
  @DisplayName("The app's client-side routes serve the app shell without a session")
  void spaRoutesServeTheAppShell() throws Exception {
    for (String route : new String[] {"/", "/login", "/dashboard"}) {
      mockMvc.perform(get(route)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }
    mockMvc
        .perform(get("/index.html"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("<div id=\"root\">")));
  }

  @Test
  @DisplayName("API errors come back as JSON, not a server-rendered error page")
  @WithMockUser(username = "testuser")
  void apiErrorsAreJson() throws Exception {
    mockMvc
        .perform(get("/api/files/download").param("path", "missing.txt"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON));
  }

  // ------------------------------
  // Controller Endpoints Tests
  // ------------------------------

  @Nested
  @DisplayName("Controller Endpoints Security")
  class ControllerSecurityTests {

    @Test
    @DisplayName("Unauthenticated user cannot access directory-info")
    void unauthenticatedUserCannotAccessDirectoryInfo() throws Exception {
      mockMvc.perform(get("/api/storage")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Authenticated user can access directory-info")
    @WithMockUser(
        username = "testuser",
        roles = {"USER"})
    void authenticatedUserCanAccessDirectoryInfo() throws Exception {
      mockMvc
          .perform(get("/api/storage"))
          .andExpect(status().isOk())
          .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }
  }

  // ------------------------------
  // Session and Logout Tests
  // ------------------------------

  @Nested
  @DisplayName("Session Management")
  class SessionTests {

    @Test
    @DisplayName("Logout should succeed")
    @WithMockUser(
        username = "testuser",
        roles = {"USER"})
    void logoutSucceeds() throws Exception {
      mockMvc.perform(post("/logout").with(csrf())).andExpect(status().isOk());
    }
  }

  // ------------------------------
  // User Role Tests
  // ------------------------------

  @Nested
  @DisplayName("User Roles and Permissions")
  class UserRoleTests {

    @Test
    @DisplayName("User with USER role can access API")
    @WithMockUser(
        username = "user",
        roles = {"USER"})
    void userRoleCanAccessApi() throws Exception {
      mockMvc.perform(get("/api/files")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("User with ADMIN role can access API")
    @WithMockUser(
        username = "admin",
        roles = {"ADMIN"})
    void adminRoleCanAccessApi() throws Exception {
      mockMvc.perform(get("/api/files")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Anonymous user explicitly denied access")
    @WithAnonymousUser
    void anonymousUserDeniedAccess() throws Exception {
      mockMvc.perform(get("/api/files")).andExpect(status().isUnauthorized());
    }
  }

  // ------------------------------
  // Content Type Tests
  // ------------------------------

  @Nested
  @DisplayName("Content Type Handling")
  class ContentTypeTests {

    @Test
    @DisplayName("JSON API endpoints return JSON when authenticated")
    @WithMockUser(
        username = "testuser",
        roles = {"USER"})
    void apiEndpointsReturnJson() throws Exception {
      mockMvc
          .perform(get("/api/storage").accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }
  }

  // ------------------------------
  // Service State Verification
  // ------------------------------

  @Test
  @DisplayName("AuthService should report setup as not required")
  void authServiceReportsSetupNotRequired() {
    assert !authService.isSetupRequired()
        : "Setup should not be required for SecurityIntegrationTests";
  }
}
