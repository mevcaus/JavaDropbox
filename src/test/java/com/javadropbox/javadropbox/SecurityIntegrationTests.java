package com.javadropbox.javadropbox;

import com.javadropbox.javadropbox.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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

    @Autowired
    private com.javadropbox.javadropbox.repository.UserRepository userRepository;

    @Autowired
    private com.javadropbox.javadropbox.repository.FileHistoryRepository fileHistoryRepository;

    @Autowired
    private com.javadropbox.javadropbox.repository.FileMetadataRepository fileMetadataRepository;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // Ensure setup is NOT required by creating a user
        if (userRepository.count() == 0) {
            com.javadropbox.javadropbox.model.User user = new com.javadropbox.javadropbox.model.User("testadmin",
                    passwordEncoder.encode("password"), "ROLE_ADMIN");
            userRepository.save(user);
        }
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        fileHistoryRepository.deleteAll();
        fileMetadataRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ------------------------------
    // Basic Authentication Tests
    // ------------------------------

    @Test
    @DisplayName("Unauthenticated user should receive 401 when accessing protected API")
    void unauthenticatedUserReceives401() throws Exception {
        mockMvc.perform(get("/api/files"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Authenticated user can access API")
    @WithMockUser(username = "testuser", roles = { "USER" })
    void authenticatedUserCanAccessApi() throws Exception {
        mockMvc.perform(get("/api/files"))
                .andExpect(status().isOk())
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
            mockMvc.perform(get("/api/directory-info"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Authenticated user can access directory-info")
        @WithMockUser(username = "testuser", roles = { "USER" })
        void authenticatedUserCanAccessDirectoryInfo() throws Exception {
            mockMvc.perform(get("/api/directory-info"))
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
        @WithMockUser(username = "testuser", roles = { "USER" })
        void logoutSucceeds() throws Exception {
            mockMvc.perform(post("/logout"))
                    .andExpect(status().isOk());
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
        @WithMockUser(username = "user", roles = { "USER" })
        void userRoleCanAccessApi() throws Exception {
            mockMvc.perform(get("/api/files"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("User with ADMIN role can access API")
        @WithMockUser(username = "admin", roles = { "ADMIN" })
        void adminRoleCanAccessApi() throws Exception {
            mockMvc.perform(get("/api/files"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Anonymous user explicitly denied access")
        @WithAnonymousUser
        void anonymousUserDeniedAccess() throws Exception {
            mockMvc.perform(get("/api/files"))
                    .andExpect(status().isUnauthorized());
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
        @WithMockUser(username = "testuser", roles = { "USER" })
        void apiEndpointsReturnJson() throws Exception {
            mockMvc.perform(get("/api/directory-info")
                    .accept(MediaType.APPLICATION_JSON))
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
        assert !authService.isSetupRequired() : "Setup should not be required for SecurityIntegrationTests";
    }
}