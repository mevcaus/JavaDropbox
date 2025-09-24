package com.javadropbox.javadropbox;

import com.javadropbox.javadropbox.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.setup.required=false",
        "app.setup.filter.enabled=false"
})
class SecurityIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

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
    @DisplayName("Static resources such as CSS are accessible without authentication")
    void cssAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/css/Login.css"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Unauthenticated users can see login page")
    void loginPageLoads() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign In")));
    }

    @Test
    @DisplayName("Unauthenticated user should not be able to reach /api/files")
    void unauthenticatedUserCannotReachApiFiles() throws Exception {
        mockMvc.perform(get("/api/files"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login*"));
    }

    @Test
    @DisplayName("Authenticated user can hit /api/files")
    @WithMockUser(username = "testuser", roles = {"USER"})
    void authenticatedUserCanReachApiFiles() throws Exception {
        mockMvc.perform(get("/api/files"))
                .andExpect(status().isOk());
    }

    @Nested
    @DisplayName("Setup Page Behavior")
    class SetupTests {

        @Test
        @DisplayName("Setup page is accessible without authentication (when required)")
        void setupPageAccessible() throws Exception {
            if (authService.isSetupRequired()) {
                mockMvc.perform(get("/setup"))
                        .andExpect(status().isOk())
                        .andExpect(view().name("Setup"));
            }
        }

        @Test
        @DisplayName("After setup, users redirected to login")
        void setupRedirectsToLoginIfAlreadyComplete() throws Exception {
            if (!authService.isSetupRequired()) {
                mockMvc.perform(get("/setup"))
                        .andExpect(status().is3xxRedirection())
                        .andExpect(redirectedUrl("/login"));
            }
        }
    }

    @Test
    @DisplayName("Logout should redirect to login page with logout param")
    void logoutRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/logout"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }

}