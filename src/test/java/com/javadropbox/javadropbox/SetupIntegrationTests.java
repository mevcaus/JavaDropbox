package com.javadropbox.javadropbox;

import com.javadropbox.javadropbox.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.setup.required=true",
        "app.setup.filter.enabled=true"
})
@DisplayName("Setup Integration Tests - Pre-Setup State")
class SetupIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private com.javadropbox.javadropbox.repository.UserRepository userRepository;

    @Autowired
    private com.javadropbox.javadropbox.repository.FileHistoryRepository fileHistoryRepository;

    @Autowired
    private com.javadropbox.javadropbox.repository.FileMetadataRepository fileMetadataRepository;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // Ensure setup IS required by clearing users
        // Must clear dependent tables first to avoid foreign key violations
        fileHistoryRepository.deleteAll();
        fileMetadataRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ------------------------------
    // Redirect Tests During Setup
    // ------------------------------

    @Test
    @DisplayName("API endpoints should redirect to setup when setup is required")
    void apiEndpointsRedirectToSetupWhenRequired() throws Exception {
        mockMvc.perform(get("/api/files"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"));

        mockMvc.perform(get("/api/directory-info"))
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
            mockMvc.perform(post("/setup")
                    .param("username", "testadmin")
                    .param("password", "testpassword123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Setup successful"));
        }

        @Test
        @DisplayName("Setup form submission with missing username should show error")
        void setupWithMissingUsernameHandled() throws Exception {
            mockMvc.perform(post("/setup")
                    .param("password", "testpassword123"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Setup form submission with missing password should show error")
        void setupWithMissingPasswordHandled() throws Exception {
            mockMvc.perform(post("/setup")
                    .param("username", "testadmin"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Setup form submission with empty values should show error")
        void setupWithEmptyValuesHandled() throws Exception {
            mockMvc.perform(post("/setup")
                    .param("username", "")
                    .param("password", ""))
                    .andExpect(status().isBadRequest());
        }
    }

    // ------------------------------
    // Authentication Tests During Setup
    // ------------------------------

    @Test
    @DisplayName("Mock authenticated user cannot access API endpoints when setup is required")
    @WithMockUser(username = "testuser", roles = { "USER" })
    void authenticatedUserCannotAccessApiDuringSetup() throws Exception {
        mockMvc.perform(get("/api/files"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"));
    }

    // ------------------------------
    // Setup Filter Tests
    // ------------------------------

    @Test
    @DisplayName("Non-existent endpoints should redirect to setup")
    void nonExistentEndpointRedirectsToSetup() throws Exception {
        mockMvc.perform(get("/nonexistent"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"));
    }

    @Test
    @DisplayName("POST to non-setup endpoints should redirect to setup")
    void postToNonSetupEndpointRedirectsToSetup() throws Exception {
        mockMvc.perform(post("/api/upload"))
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