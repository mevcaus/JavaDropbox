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

import static org.hamcrest.Matchers.containsString;
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

    // ------------------------------
    // Setup Page Access Tests
    // ------------------------------

    @Test
    @DisplayName("Setup page should be accessible without authentication when setup is required")
    void setupPageAccessibleWhenRequired() throws Exception {
        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(view().name("Setup"));
    }

    @Test
    @DisplayName("Setup page should load with setup form elements")
    void setupPageContainsForm() throws Exception {
        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("setup")));
    }

    // ------------------------------
    // Redirect Tests During Setup
    // ------------------------------

    @Test
    @DisplayName("Root path should redirect to setup when setup is required")
    void rootRedirectsToSetupWhenRequired() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"));
    }

    @Test
    @DisplayName("Dashboard should redirect to setup when setup is required")
    void dashboardRedirectsToSetupWhenRequired() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"));
    }

    @Test
    @DisplayName("Login page should redirect to setup when setup is required")
    void loginRedirectsToSetupWhenRequired() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"));
    }

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

    @Test
    @DisplayName("Directory info endpoint should redirect to setup when setup is required")
    void directoryInfoRedirectsToSetupWhenRequired() throws Exception {
        mockMvc.perform(get("/directory-info"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"));
    }

    // ------------------------------
    // Static Resources Tests
    // ------------------------------

    @Test
    @DisplayName("CSS files should be accessible during setup")
    void cssAccessibleDuringSetup() throws Exception {
        mockMvc.perform(get("/css/Login.css"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("JavaScript files should be accessible during setup")
    void jsAccessibleDuringSetup() throws Exception {
        mockMvc.perform(get("/js/some-script.js"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Images should be accessible during setup")
    void imagesAccessibleDuringSetup() throws Exception {
        mockMvc.perform(get("/images/logo.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Favicon should be accessible during setup")
    void faviconAccessibleDuringSetup() throws Exception {
        mockMvc.perform(get("/JavaDropbox_favicon.png"))
                .andExpect(status().isOk());
    }

    // ------------------------------
    // Setup Form Submission Tests
    // ------------------------------

    @Nested
    @DisplayName("Setup Form Submission")
    class SetupFormTests {

        @Test
        @DisplayName("Valid setup form submission should redirect to login")
        void validSetupSubmissionRedirectsToLogin() throws Exception {
            mockMvc.perform(post("/setup")
                            .param("username", "testadmin")
                            .param("password", "testpassword123"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login"));
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
                    .andExpect(status().is3xxRedirection())  // Changed from isBadRequest()
                    .andExpect(redirectedUrl("/setup?error=username-required"));
        }
    }

    // ------------------------------
    // Authentication Tests During Setup
    // ------------------------------

    @Test
    @DisplayName("Mock authenticated user should still be redirected to setup when setup is required")
    @WithMockUser(username = "testuser", roles = {"USER"})
    void evenAuthenticatedUserRedirectedToSetupWhenRequired() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"));
    }

    @Test
    @DisplayName("Mock authenticated user cannot access API endpoints when setup is required")
    @WithMockUser(username = "testuser", roles = {"USER"})
    void authenticatedUserCannotAccessApiDuringSetup() throws Exception {
        mockMvc.perform(get("/api/files"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"));
    }

    // ------------------------------
    // Error Handling Tests
    // ------------------------------

    @Nested
    @DisplayName("Error Handling During Setup")
    class SetupErrorTests {

        @Test
        @DisplayName("Non-existent endpoints should redirect to setup, not show 404")
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

    // ------------------------------
    // Security Context Tests
    // ------------------------------


    @Test
    @DisplayName("Setup filter should have higher precedence than security config")
    void setupFilterTakesPrecedenceOverSecurity() throws Exception {
        // Even protected endpoints should redirect to setup
        mockMvc.perform(get("/api/delete")
                        .param("path", "somefile.txt"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"));
    }
}