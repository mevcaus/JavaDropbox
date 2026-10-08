package com.javadropbox.javadropbox;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.HttpHeaders;
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

  // Creating accounts also makes setup no longer required. Each signed-in test user has one: a
  // session whose account is gone is signed out.
  @BeforeEach
  void setUp() {
    if (userRepository.count() == 0) {
      userRepository.save(new User("testadmin", passwordEncoder.encode("password"), "ROLE_ADMIN"));
      userRepository.save(new User("testuser", "unused", "ROLE_USER"));
      userRepository.save(new User("user", "unused", "ROLE_USER"));
      userRepository.save(new User("admin", "unused", "ROLE_ADMIN"));
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
  @DisplayName("The demo's public endpoint does not exist outside the demo profile")
  void demoEndpointIsMissingOutsideTheDemo() throws Exception {
    mockMvc.perform(get("/api/demo")).andExpect(status().isNotFound());
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

  // What a browser sends when it opens a page; axios sends application/json, text/plain, */*.
  private static final String BROWSER_ACCEPT =
      "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

  @Test
  @DisplayName("Any other page a browser opens gets the app shell, so the app can redirect it")
  void unknownPagesServeTheAppShell() throws Exception {
    for (String path : new String[] {"/no/such/page", "/dashbord", "/dashboard/extra/"}) {
      mockMvc
          .perform(get(path).header(HttpHeaders.ACCEPT, BROWSER_ACCEPT))
          .andExpect(status().isOk())
          .andExpect(forwardedUrl("/index.html"));
    }
  }

  @Test
  @DisplayName("The links admins send open the app without a session, and its API answers them")
  void accountLinkPagesAreTheAppShell() throws Exception {
    // Tokens are base64url: letters, digits, - and _, never a dot.
    String token = "Ab-9_xYz0123456789abcdefghijklmnopqrstuvwxyz";
    for (String page : new String[] {"/invite/" + token, "/reset-password/" + token}) {
      mockMvc
          .perform(get(page).with(anonymous()).header(HttpHeaders.ACCEPT, BROWSER_ACCEPT))
          .andExpect(status().isOk())
          .andExpect(forwardedUrl("/index.html"));
    }
    // What the page reads is public too: an unknown token is a 404, not a 401.
    for (String api : new String[] {"/api/invite/" + token, "/api/reset-password/" + token}) {
      mockMvc.perform(get(api).with(anonymous())).andExpect(status().isNotFound());
    }
  }

  @Test
  @DisplayName(
      "Only page navigations get the shell; API calls, share links and files answer as before")
  void onlyNavigationsGetTheShell() throws Exception {
    // Not asking for HTML: an API client, or no Accept header at all.
    mockMvc
        .perform(
            get("/no/such/page").header(HttpHeaders.ACCEPT, "application/json, text/plain, */*"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/no/such/page")).andExpect(status().isUnauthorized());
    // HTML explicitly refused.
    mockMvc
        .perform(get("/no/such/page").header(HttpHeaders.ACCEPT, "text/html;q=0, */*"))
        .andExpect(status().isUnauthorized());
    // Not a GET.
    mockMvc
        .perform(post("/no/such/page").with(csrf()).header(HttpHeaders.ACCEPT, BROWSER_ACCEPT))
        .andExpect(status().isUnauthorized());
    // The server's own paths keep their answers, even when a browser asks for HTML.
    mockMvc
        .perform(get("/api/no-such-endpoint").header(HttpHeaders.ACCEPT, BROWSER_ACCEPT))
        .andExpect(status().isUnauthorized());
    // A share link's own page is the app (see ShareLinkIntegrationTests), but what it reads is not.
    for (String path :
        new String[] {
          "/share/not-a-token/info", "/share/not-a-token/preview", "/share/not-a-token/download"
        }) {
      mockMvc
          .perform(get(path).header(HttpHeaders.ACCEPT, BROWSER_ACCEPT))
          .andExpect(status().isNotFound())
          .andExpect(forwardedUrl(null));
    }
    // A missing file is a 404, not the app.
    mockMvc
        .perform(get("/assets/missing.js").header(HttpHeaders.ACCEPT, BROWSER_ACCEPT))
        .andExpect(status().isNotFound())
        .andExpect(forwardedUrl(null));
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
    @DisplayName("Unauthenticated user cannot see what is stored")
    void unauthenticatedUserCannotAccessStorageUse() throws Exception {
      mockMvc.perform(get("/api/storage")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Authenticated user sees what they store and their quota, not the server's path")
    @WithMockUser(
        username = "testuser",
        roles = {"USER"})
    void authenticatedUserCanAccessStorageUse() throws Exception {
      mockMvc
          .perform(get("/api/storage"))
          .andExpect(status().isOk())
          .andExpect(content().contentType(MediaType.APPLICATION_JSON))
          .andExpect(jsonPath("$.usedBytes").value(0))
          .andExpect(jsonPath("$.quotaBytes").doesNotExist())
          .andExpect(jsonPath("$.path").doesNotExist());
    }

    @Test
    @DisplayName("A user cannot reach the admin endpoints")
    @WithMockUser(
        username = "testuser",
        roles = {"USER"})
    void userCannotReachAdminEndpoints() throws Exception {
      mockMvc.perform(get("/api/admin/users")).andExpect(status().isForbidden());
      mockMvc
          .perform(post("/api/admin/invites").param("username", "eve").with(csrf()))
          .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A signed-in principal whose account is gone is treated as signed out")
    @WithMockUser(username = "ghost")
    void principalWithoutAccountIsSignedOut() throws Exception {
      mockMvc.perform(get("/api/files")).andExpect(status().isUnauthorized());
      mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
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
