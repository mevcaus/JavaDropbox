package com.javadropbox.javadropbox;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.cors.allowed-origins=http://localhost:5173")
@AutoConfigureMockMvc
@DisplayName("CORS")
class CorsIntegrationTests {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("a configured origin may call the API with credentials")
  void configuredOriginAllowed() throws Exception {
    mockMvc
        .perform(
            options("/api/files")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
  }

  @Test
  @DisplayName("any other origin is refused")
  void otherOriginRefused() throws Exception {
    mockMvc
        .perform(
            options("/api/files")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden());
  }
}
