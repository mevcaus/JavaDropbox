package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.javadropbox.javadropbox.config.SetupFilter;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileHistoryRepository;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.setup.code=ABCDE-FGHJK"})
@Testcontainers
@DisplayName("Setup Integration Tests - Pre-Setup State")
class SetupIntegrationTests {

  // Runs against a real PostgreSQL container with the schema built by the
  // Flyway migrations, rather than the H2 default in
  // src/test/resources/application.properties, so this class exercises the
  // database the app actually ships against. The other integration tests stay
  // on H2 for speed.
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(PostgresTestSupport.IMAGE);

  @DynamicPropertySource
  static void overrideDatasource(DynamicPropertyRegistry registry) {
    PostgresTestSupport.register(registry, POSTGRES);
  }

  @Autowired private MockMvc mockMvc;

  @Autowired private AuthService authService;

  @Autowired private UserRepository userRepository;

  @Autowired private FileHistoryRepository fileHistoryRepository;

  @Autowired private FileMetadataRepository fileMetadataRepository;

  @Autowired private SetupFilter setupFilter;

  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    // The filter remembers that setup is done, which is true in production where accounts are
    // never deleted, but these tests delete them to get back to a fresh install.
    ReflectionTestUtils.setField(setupFilter, "setupComplete", false);
    TestDatabase.wipe(jdbc);
  }

  // ------------------------------
  // Redirect Tests During Setup
  // ------------------------------

  @Test
  @DisplayName("API endpoints should redirect to setup when setup is required")
  void apiEndpointsRedirectToSetupWhenRequired() throws Exception {
    mockMvc
        .perform(get("/api/files"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/setup"));

    mockMvc
        .perform(get("/api/storage"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/setup"));
  }

  // ------------------------------
  // Setup Form Submission Tests
  // ------------------------------

  @Nested
  @DisplayName("Setup Form Submission")
  class SetupFormTests {

    @Test
    @DisplayName("Valid setup form submission should succeed")
    void validSetupSubmissionSucceeds() throws Exception {
      mockMvc
          .perform(
              post("/setup")
                  .with(csrf())
                  .param("code", "ABCDE-FGHJK")
                  .param("username", "testadmin")
                  .param("password", "testpassword123"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.message").value("Setup successful"));

      assertThat(userRepository.findByUsername("testadmin"))
          .hasValueSatisfying(u -> assertThat(u.getRole()).isEqualTo("ROLE_ADMIN"));
    }

    @Test
    @DisplayName("The setup code is accepted without its dash and in lower case")
    void setupCodeIsForgivingAboutFormatting() throws Exception {
      mockMvc
          .perform(
              post("/setup")
                  .with(csrf())
                  .param("code", "abcdefghjk")
                  .param("username", "testadmin")
                  .param("password", "testpassword123"))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Setup without the code printed in the server log is refused")
    void setupWithoutCodeIsRefused() throws Exception {
      mockMvc
          .perform(
              post("/setup")
                  .with(csrf())
                  .param("username", "intruder")
                  .param("password", "testpassword123"))
          .andExpect(status().isForbidden());
      mockMvc
          .perform(
              post("/setup")
                  .with(csrf())
                  .param("code", "WRONG-CODE0")
                  .param("username", "intruder")
                  .param("password", "testpassword123"))
          .andExpect(status().isForbidden());

      assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("A client that keeps sending wrong codes is throttled; others are not")
    void repeatedWrongCodesAreThrottledPerClient() throws Exception {
      for (int i = 0; i < 5; i++) {
        mockMvc
            .perform(
                post("/setup")
                    .with(csrf())
                    .with(remoteAddr("198.51.100.66"))
                    .param("code", "WRONG-CODE" + i)
                    .param("username", "intruder")
                    .param("password", "testpassword123"))
            .andExpect(status().isForbidden());
      }

      mockMvc
          .perform(
              post("/setup")
                  .with(csrf())
                  .with(remoteAddr("198.51.100.66"))
                  .param("code", "ABCDE-FGHJK")
                  .param("username", "intruder")
                  .param("password", "testpassword123"))
          .andExpect(status().isTooManyRequests())
          .andExpect(header().exists("Retry-After"));
      mockMvc
          .perform(
              post("/setup")
                  .with(csrf())
                  .with(remoteAddr("192.0.2.1"))
                  .param("code", "ABCDE-FGHJK")
                  .param("username", "testadmin")
                  .param("password", "testpassword123"))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Setup form submission with missing username should show error")
    void setupWithMissingUsernameHandled() throws Exception {
      mockMvc
          .perform(
              post("/setup")
                  .with(csrf())
                  .param("code", "ABCDE-FGHJK")
                  .param("password", "testpassword123"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Setup form submission with missing password should show error")
    void setupWithMissingPasswordHandled() throws Exception {
      mockMvc
          .perform(
              post("/setup").with(csrf()).param("code", "ABCDE-FGHJK").param("username", "admin"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A password shorter than eight characters is refused")
    void shortPasswordRefused() throws Exception {
      mockMvc
          .perform(
              post("/setup")
                  .with(csrf())
                  .param("code", "ABCDE-FGHJK")
                  .param("username", "admin")
                  .param("password", "short"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("Password must be at least 8 characters"));
    }

    @Test
    @DisplayName("Setup form submission with empty values should show error")
    void setupWithEmptyValuesHandled() throws Exception {
      mockMvc
          .perform(
              post("/setup")
                  .with(csrf())
                  .param("code", "ABCDE-FGHJK")
                  .param("username", "")
                  .param("password", ""))
          .andExpect(status().isBadRequest());
    }
  }

  private static RequestPostProcessor remoteAddr(String address) {
    return request -> {
      request.setRemoteAddr(address);
      return request;
    };
  }

  // ------------------------------
  // Authentication Tests During Setup
  // ------------------------------

  @Test
  @DisplayName("Mock authenticated user cannot access API endpoints when setup is required")
  @WithMockUser(
      username = "testuser",
      roles = {"USER"})
  void authenticatedUserCannotAccessApiDuringSetup() throws Exception {
    mockMvc
        .perform(get("/api/files"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/setup"));
  }

  // ------------------------------
  // Setup Filter Tests
  // ------------------------------

  @Test
  @DisplayName("The app shell and its assets load during setup so the setup page can render")
  void appShellLoadsDuringSetup() throws Exception {
    mockMvc.perform(get("/setup")).andExpect(forwardedUrl("/index.html"));
    mockMvc.perform(get("/index.html")).andExpect(status().isOk());
    mockMvc
        .perform(get("/dashboard"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/setup"));
    // An unknown page is not let past setup either.
    mockMvc
        .perform(get("/no/such/page").header("Accept", "text/html"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/setup"));
  }

  @Test
  @DisplayName("Once an account exists, a second setup attempt is a 409, not a redirect")
  void setupAfterCompletionIsConflict() throws Exception {
    userRepository.save(new User("admin", "hash", "ROLE_ADMIN"));

    mockMvc
        .perform(
            post("/setup")
                .with(csrf())
                .param("code", "ABCDE-FGHJK")
                .param("username", "second")
                .param("password", "testpassword123"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(get("/setup"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login"));
  }

  @Test
  @DisplayName("Non-existent endpoints should redirect to setup")
  void nonExistentEndpointRedirectsToSetup() throws Exception {
    mockMvc
        .perform(get("/nonexistent"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/setup"));
  }

  @Test
  @DisplayName("POST to non-setup endpoints should redirect to setup")
  void postToNonSetupEndpointRedirectsToSetup() throws Exception {
    mockMvc
        .perform(post("/api/files").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/setup"));
  }

  // ------------------------------
  // ✅ Service State Verification
  // ------------------------------

  @Test
  @DisplayName("AuthService should report setup as required")
  void authServiceReportsSetupRequired() throws Exception {
    // Verify our test configuration is working
    assert authService.isSetupRequired();
  }
}
