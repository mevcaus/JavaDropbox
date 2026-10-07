package com.javadropbox.javadropbox;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Swagger and OpenAPI Integration Tests")
class SwaggerIntegrationTests {

  @Autowired private MockMvc mockMvc;

  @Autowired private UserRepository userRepository;

  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private JdbcTemplate jdbc;

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  private void completeSetup() {
    if (userRepository.count() == 0) {
      userRepository.save(new User("testadmin", passwordEncoder.encode("password"), "ROLE_ADMIN"));
    }
  }

  @Test
  @DisplayName("Swagger UI is reachable before setup is completed")
  void swaggerUiReachableBeforeSetup() throws Exception {
    // No user exists, so SetupFilter treats this as a fresh install and redirects
    // everything outside its allowlist to /setup. The docs must not be swept up in that.
    mockMvc
        .perform(get("/swagger-ui.html"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/swagger-ui/index.html"));

    mockMvc
        .perform(get("/swagger-ui/index.html"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Swagger UI")));
  }

  @Test
  @DisplayName("OpenAPI JSON docs are reachable before setup is completed")
  void openApiDocsReachableBeforeSetup() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"openapi\"")));
  }

  @Test
  @DisplayName("Swagger UI is reachable without authentication after setup")
  void swaggerUiReachableWithoutAuthAfterSetup() throws Exception {
    completeSetup();

    mockMvc
        .perform(get("/swagger-ui/index.html"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Swagger UI")));

    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("OpenAPI spec documents every controller and its endpoints")
  void openApiSpecDocumentsAllEndpoints() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"name\":\"Files\"")))
        .andExpect(content().string(containsString("\"name\":\"File Versions\"")))
        .andExpect(content().string(containsString("\"name\":\"History\"")))
        .andExpect(content().string(containsString("\"name\":\"Setup\"")))
        .andExpect(content().string(containsString("\"name\":\"Share\"")))
        .andExpect(content().string(containsString("\"name\":\"Search\"")))
        .andExpect(content().string(containsString("\"/api/files\"")))
        .andExpect(content().string(containsString("\"/api/files/download\"")))
        .andExpect(content().string(containsString("\"/api/folders\"")))
        .andExpect(content().string(containsString("\"/api/storage\"")))
        .andExpect(content().string(containsString("\"/api/search\"")))
        .andExpect(content().string(containsString("\"/api/history\"")))
        .andExpect(content().string(containsString("\"/api/share\"")))
        .andExpect(content().string(containsString("\"/share/{token}\"")))
        .andExpect(content().string(containsString("\"/share/{token}/info\"")))
        .andExpect(content().string(containsString("\"/share/{token}/preview\"")))
        .andExpect(content().string(containsString("\"/share/{token}/download\"")))
        .andExpect(content().string(containsString("\"/api/files/{fileId}/versions\"")))
        .andExpect(content().string(containsString("\"/setup\"")));
  }
}
