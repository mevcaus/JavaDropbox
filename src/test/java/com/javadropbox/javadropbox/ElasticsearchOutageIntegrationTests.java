package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.SearchIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Elasticsearch going away under a running app, through a proxy the test stops and starts: only
 * search may break.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "owner", roles = "ADMIN")
@DisplayName("Search on Elasticsearch, while Elasticsearch is down")
@TempServingDirectory
class ElasticsearchOutageIntegrationTests {

  private static final Duration INDEXING = Duration.ofSeconds(30);
  // Long enough for the first retry, five seconds after a batch couldn't be written.
  private static final Duration CATCHING_UP = Duration.ofMinutes(1);
  private static final String UNAVAILABLE =
      "Search can't be reached right now. Your files are fine. Please try again in a moment.";

  private static TcpProxy elasticsearch;

  @DynamicPropertySource
  static void elasticsearch(DynamicPropertyRegistry registry) {
    elasticsearch = new TcpProxy(ElasticsearchTestSupport.url());
    ElasticsearchTestSupport.register(registry, elasticsearch::url);
  }

  @Value("${javadropbox.serving.directory}")
  private Path servingDir;

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private SearchIndex searchIndex;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;

  @BeforeEach
  void setUp() {
    users.save(new User("owner", "unused", "ROLE_ADMIN"));
  }

  @AfterEach
  void tearDown() throws Exception {
    elasticsearch.start();
    TestDatabase.wipe(jdbc);
    TestStorage.empty(servingDir);
    searchIndex.reconcile();
    searchIndex.awaitIdle(CATCHING_UP);
  }

  @Test
  @DisplayName(
      "search answers 503 while uploads and deletes still work, and it catches up once it is back")
  void onlySearchBreaks() throws Exception {
    upload("kept.txt", "written before the outage");
    upload("doomed.txt", "written before the outage too");
    searchIndex.awaitIdle(INDEXING);
    assertThat(paths(search("outage"))).containsExactlyInAnyOrder("kept.txt", "doomed.txt");

    elasticsearch.stop();

    mockMvc
        .perform(get("/api/search").param("q", "outage"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.message").value(UNAVAILABLE));
    upload("during.txt", "written during the outage");
    mockMvc
        .perform(delete("/api/files").param("path", "doomed.txt").with(csrf()))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/files")).andExpect(status().isOk());
    // The app stays up, for a load balancer to keep sending it requests; an admin sees why search
    // isn't.
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components.search.status").value("DEGRADED"))
        .andExpect(jsonPath("$.components.search.details.backend").value("elasticsearch"))
        .andExpect(
            jsonPath("$.components.search.details.problem")
                .value(org.hamcrest.Matchers.containsString("can't be reached")));

    elasticsearch.start();
    searchIndex.awaitIdle(CATCHING_UP);

    JsonNode answer = search("outage");
    assertThat(paths(answer)).containsExactlyInAnyOrder("kept.txt", "during.txt");
    assertThat(answer.path("indexing").asBoolean()).isFalse();
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(jsonPath("$.components.search.status").value("UP"))
        .andExpect(jsonPath("$.components.search.details.problem").doesNotExist());
  }

  private void upload(String name, String text) throws Exception {
    mockMvc
        .perform(
            multipart("/api/files")
                .file(
                    new MockMultipartFile(
                        "files",
                        name,
                        "application/octet-stream",
                        text.getBytes(StandardCharsets.UTF_8)))
                .param("path", "")
                .with(csrf().asHeader()))
        .andExpect(status().isOk());
  }

  private JsonNode search(String q) throws Exception {
    return json.readTree(
        mockMvc
            .perform(get("/api/search").param("q", q))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8));
  }

  private static List<String> paths(JsonNode answer) {
    List<String> paths = new ArrayList<>();
    answer.path("results").forEach(result -> paths.add(result.path("relativePath").asText()));
    return paths;
  }
}
