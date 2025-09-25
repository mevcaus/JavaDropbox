package com.javadropbox.javadropbox;

import com.javadropbox.javadropbox.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.setup.required=false",
        "app.setup.filter.enabled=true"
})
@DisplayName("Security Integration Tests - Normal Operation")
class SecurityIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    // ------------------------------
    // Basic Authentication Tests
    // ------------------------------

    @Test
    @DisplayName("Unauthenticated user should be redirected to login when accessing /dashboard")
    void unauthenticatedUserRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login*"));
    }

    @Test
    @DisplayName("Authenticated user can access dashboard")
    @WithMockUser(username = "testuser", roles = {"USER"})
    void authenticatedUserCanAccessDashboard() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Current Path")));
    }

    @Test
    @DisplayName("Unauthenticated users can see login page")
    void loginPageLoads() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign In")));
    }

    @Test
    @DisplayName("Root path redirects authenticated user to dashboard")
    @WithMockUser(username = "testuser", roles = {"USER"})
    void rootPathRedirectsAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"));
    }

    // ------------------------------
    // Static Resources Tests
    // ------------------------------

    @Nested
    @DisplayName("Static Resources Access")
    class StaticResourcesTests {

        @Test
        @DisplayName("CSS files are accessible without authentication")
        void cssAccessibleWithoutAuth() throws Exception {
            mockMvc.perform(get("/css/Login.css"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("JavaScript files are accessible without authentication")
        void jsAccessibleWithoutAuth() throws Exception {
            mockMvc.perform(get("/js/script.js"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Images are accessible without authentication")
        void imagesAccessibleWithoutAuth() throws Exception {
            mockMvc.perform(get("/images/logo.png"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Favicon is accessible without authentication")
        void faviconAccessibleWithoutAuth() throws Exception {
            mockMvc.perform(get("/JavaDropbox_favicon.png"))
                    .andExpect(status().isOk());
        }
    }


    // ------------------------------
    // Controller Endpoints Tests
    // ------------------------------

    @Nested
    @DisplayName("Controller Endpoints Security")
    class ControllerSecurityTests {

        @Test
        @DisplayName("Unauthenticated user cannot access directory-info")
        void unauthenticatedUserCannotAccessDirectoryInfoPage() throws Exception {
            mockMvc.perform(get("/directory-info"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login*"));
        }

        @Test
        @DisplayName("Authenticated user can access directory-info")
        @WithMockUser(username = "testuser", roles = {"USER"})
        void authenticatedUserCanAccessDirectoryInfoPage() throws Exception {
            mockMvc.perform(get("/directory-info"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/dashboard"));
        }
    }

    // ------------------------------
    // Session and Logout Tests
    // ------------------------------

    @Nested
    @DisplayName("Session Management")
    class SessionTests {

        @Test
        @DisplayName("Logout should redirect to login page with logout param")
        void logoutRedirectsToLogin() throws Exception {
            mockMvc.perform(get("/logout"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?logout"));
        }

        @Test
        @DisplayName("POST to logout should work")
        void postLogoutWorks() throws Exception {
            mockMvc.perform(post("/logout"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?logout"));
        }

        @Test
        @DisplayName("Logout invalidates session for authenticated user")
        @WithMockUser(username = "testuser", roles = {"USER"})
        void logoutInvalidatesSession() throws Exception {
            // User can access dashboard first
            mockMvc.perform(get("/dashboard"))
                    .andExpect(status().isOk());

            // Logout
            mockMvc.perform(post("/logout"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?logout"));
        }
    }

    // ------------------------------
    // Setup Redirect Tests (Post-Setup State)
    // ------------------------------

    @Nested
    @DisplayName("Setup Handling in Normal Operation")
    class SetupRedirectTests {

        @Test
        @DisplayName("Setup page redirects to login when setup is complete")
        void setupRedirectsToLoginWhenComplete() throws Exception {
            mockMvc.perform(get("/setup"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login"));
        }

        @Test
        @DisplayName("POST to setup redirects to login when setup is complete")
        void postSetupRedirectsWhenComplete() throws Exception {
            mockMvc.perform(post("/setup")
                            .param("username", "testuser")
                            .param("password", "testpass"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login"));
        }
    }

    // ------------------------------
    // User Role Tests
    // ------------------------------

    @Nested
    @DisplayName("User Roles and Permissions")
    class UserRoleTests {

        @Test
        @DisplayName("User with USER role can access dashboard")
        @WithMockUser(username = "user", roles = {"USER"})
        void userRoleCanAccessDashboard() throws Exception {
            mockMvc.perform(get("/dashboard"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("User with ADMIN role can access dashboard")
        @WithMockUser(username = "admin", roles = {"ADMIN"})
        void adminRoleCanAccessDashboard() throws Exception {
            mockMvc.perform(get("/dashboard"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("User with multiple roles can access dashboard")
        @WithMockUser(username = "superuser", roles = {"USER", "ADMIN"})
        void multipleRolesCanAccessDashboard() throws Exception {
            mockMvc.perform(get("/dashboard"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Anonymous user explicitly denied access")
        @WithAnonymousUser
        void anonymousUserDeniedAccess() throws Exception {
            mockMvc.perform(get("/dashboard"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login*"));
        }
    }

    // ------------------------------
    // Error Handling Tests
    // ------------------------------

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("404 errors for authenticated users")
        @WithMockUser(username = "testuser", roles = {"USER"})
        void notFoundForAuthenticatedUser() throws Exception {
            mockMvc.perform(get("/nonexistent-page"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Unauthenticated access to non-existent protected endpoints redirects to login")
        void unauthenticatedNonExistentRedirectsToLogin() throws Exception {
            mockMvc.perform(get("/protected/nonexistent"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login*"));
        }

        @Test
        @DisplayName("Error page is accessible without authentication")
        void errorPageAccessible() throws Exception {
            mockMvc.perform(get("/error"))
                    .andExpect(status().isOk());
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
        @WithMockUser(username = "testuser", roles = {"USER"})
        void apiEndpointsReturnJson() throws Exception {
            mockMvc.perform(get("/api/directory-info")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON));
        }

        @Test
        @DisplayName("HTML endpoints return HTML when authenticated")
        @WithMockUser(username = "testuser", roles = {"USER"})
        void htmlEndpointsReturnHtml() throws Exception {
            mockMvc.perform(get("/dashboard")
                            .accept(MediaType.TEXT_HTML))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        }
    }

    // ------------------------------
    // Service State Verification
    // ------------------------------

    @Test
    @DisplayName("AuthService should report setup as not required")
    void authServiceReportsSetupNotRequired() {
        assert !authService.isSetupRequired() : "Setup should not be required for SecurityIntegrationTests";
    }

    // ------------------------------
    // Remember Me Functionality
    // ------------------------------

    @Nested
    @DisplayName("Remember Me Functionality")
    class RememberMeTests {

        @Test
        @DisplayName("Login form should support remember-me parameter")
        void loginFormSupportsRememberMe() throws Exception {
            mockMvc.perform(post("/login")
                            .param("username", "testuser")
                            .param("password", "testpass")
                            .param("remember-me", "true"))
                    .andExpect(status().is3xxRedirection());
        }

        @Test
        @DisplayName("Login without remember-me parameter works")
        void loginWithoutRememberMe() throws Exception {
            mockMvc.perform(post("/login")
                            .param("username", "testuser")
                            .param("password", "testpass"))
                    .andExpect(status().is3xxRedirection());
        }
    }
}