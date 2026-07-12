package com.javadropbox.javadropbox;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.ShareTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.setup.required=false",
        "app.setup.filter.enabled=true"
})
@DisplayName("Share Link Integration Tests")
class ShareLinkIntegrationTests {

    @TempDir
    static Path servingDir;

    @DynamicPropertySource
    static void overrideServingDirectory(DynamicPropertyRegistry registry) {
        registry.add("javadropbox.serving.directory", () -> servingDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShareTokenService shareTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() throws IOException {
        if (userRepository.count() == 0) {
            userRepository.save(new User("testadmin", passwordEncoder.encode("password"), "ROLE_ADMIN"));
        }
        Files.writeString(servingDir.resolve("shared.txt"), "share me");
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Unauthenticated user cannot create a share link")
    void unauthenticatedCannotCreateShareLink() throws Exception {
        mockMvc.perform(post("/api/share").param("path", "shared.txt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Authenticated user can create a share link for an existing file")
    @WithMockUser(username = "testuser", roles = { "USER" })
    void authenticatedUserCanCreateShareLink() throws Exception {
        mockMvc.perform(post("/api/share").param("path", "shared.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(containsString("/share/")))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    @DisplayName("Creating a share link for a nonexistent path returns 404")
    @WithMockUser(username = "testuser", roles = { "USER" })
    void shareLinkForMissingPathReturns404() throws Exception {
        mockMvc.perform(post("/api/share").param("path", "nope.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Out-of-range expiration is rejected")
    @WithMockUser(username = "testuser", roles = { "USER" })
    void outOfRangeExpirationRejected() throws Exception {
        mockMvc.perform(post("/api/share").param("path", "shared.txt").param("expirationMinutes", "0"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/share").param("path", "shared.txt").param("expirationMinutes", "999999"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A valid share link downloads the file without authentication")
    void validTokenDownloadsWithoutAuth() throws Exception {
        String token = shareTokenService.generateToken("shared.txt", 60);

        mockMvc.perform(get("/share/" + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("shared.txt")))
                .andExpect(content().string("share me"));
    }

    @Test
    @DisplayName("An expired token is rejected")
    void expiredTokenRejected() throws Exception {
        String token = shareTokenService.generateToken("shared.txt", -1);

        mockMvc.perform(get("/share/" + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A tampered or garbage token is rejected")
    void garbageTokenRejected() throws Exception {
        mockMvc.perform(get("/share/not-a-real-token"))
                .andExpect(status().isNotFound());
    }
}
