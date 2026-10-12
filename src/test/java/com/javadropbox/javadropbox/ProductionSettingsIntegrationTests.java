package com.javadropbox.javadropbox;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The app as application.properties configures a real install. The tests' own settings only add to
 * it (src/test/resources/config/application.properties), so what production turns off is off here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Production settings")
class ProductionSettingsIntegrationTests {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("the API docs and Swagger UI are not published")
  void apiDocsAreNotPublished() throws Exception {
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
  }
}
