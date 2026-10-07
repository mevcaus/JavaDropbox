package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadropbox.javadropbox.config.SetupFilter;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.FileSystemUtils;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Observability Integration Tests")
class ObservabilityIntegrationTests {

  private static final String FILES_SERVED = "javadropbox.files.served";
  private static final String UPLOAD_SIZE = "javadropbox.uploads.size";
  private static final String SHARE_LINKS_CREATED = "javadropbox.share.links.created";

  @TempDir static Path servingDir;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
    // What production exposes, not Spring Boot's defaults (see MainProperties).
    MainProperties.register(
        registry,
        "management.endpoints.web.exposure.include",
        "management.endpoint.health.show-details",
        "management.health.diskspace.path");
  }

  @Autowired private MockMvc mockMvc;

  @Autowired private UserRepository userRepository;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private ObjectMapper json;

  @Autowired private SetupFilter setupFilter;

  @AfterEach
  void tearDown() throws IOException {
    TestDatabase.wipe(jdbc);
    try (var entries = Files.list(servingDir)) {
      for (Path entry : entries.toList()) {
        FileSystemUtils.deleteRecursively(entry);
      }
    }
  }

  private void completeSetup() {
    if (userRepository.count() == 0) {
      userRepository.save(new User("testadmin", "unused", "ROLE_ADMIN"));
    }
  }

  @Test
  @DisplayName("the health check answers before setup, where everything else redirects")
  void healthBeforeSetup() throws Exception {
    // SetupFilter stops checking once it has seen an account, which an earlier test in this
    // context may have created.
    ReflectionTestUtils.setField(setupFilter, "setupComplete", false);
    mockMvc.perform(get("/api/files")).andExpect(status().is3xxRedirection());

    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  @DisplayName("the health check is public but only says UP or DOWN without a session")
  void anonymousHealthHasNoDetails() throws Exception {
    completeSetup();

    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist());
  }

  @Test
  @WithMockUser(username = "testadmin")
  @DisplayName("signed in, the health check shows the database and the storage disk")
  void signedInHealthShowsComponents() throws Exception {
    completeSetup();

    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.db.status").value("UP"))
        .andExpect(jsonPath("$.components.diskSpace.status").value("UP"))
        .andExpect(
            jsonPath("$.components.diskSpace.details.path")
                .value(servingDir.toFile().getAbsolutePath()));
  }

  @Test
  @DisplayName("metrics need a session, also when a browser opens them")
  void metricsNeedASession() throws Exception {
    completeSetup();

    mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/actuator/metrics/" + FILES_SERVED)).andExpect(status().isUnauthorized());
    // Not answered with the app shell, as a page the server has no route for would be.
    mockMvc
        .perform(get("/actuator/metrics").accept(MediaType.TEXT_HTML))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(username = "testadmin")
  @DisplayName("only health and metrics are exposed")
  void otherEndpointsAreNotExposed() throws Exception {
    completeSetup();

    mockMvc
        .perform(get("/actuator/metrics"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.names").isArray());
    for (String endpoint : new String[] {"env", "beans", "configprops", "heapdump", "loggers"}) {
      mockMvc.perform(get("/actuator/" + endpoint)).andExpect(status().isNotFound());
    }
  }

  @Test
  @WithMockUser(username = "testadmin")
  @DisplayName("each uploaded file is counted with its size")
  void uploadsRecordTheirSize() throws Exception {
    completeSetup();
    double count = metric(UPLOAD_SIZE, "COUNT");
    double total = metric(UPLOAD_SIZE, "TOTAL");

    mockMvc
        .perform(
            multipart("/api/files")
                .file(new MockMultipartFile("files", "a.txt", "text/plain", new byte[100]))
                .file(new MockMultipartFile("files", "b.txt", "text/plain", new byte[23]))
                .param("path", "")
                .with(csrf().asHeader()))
        .andExpect(status().isOk());

    assertThat(metric(UPLOAD_SIZE, "COUNT")).isEqualTo(count + 2);
    assertThat(metric(UPLOAD_SIZE, "TOTAL")).isEqualTo(total + 123);
  }

  @Test
  @WithMockUser(username = "testadmin")
  @DisplayName("a refused upload is not counted")
  void refusedUploadIsNotCounted() throws Exception {
    completeSetup();
    Files.writeString(servingDir.resolve("a.txt"), "a file, not a folder");
    double count = metric(UPLOAD_SIZE, "COUNT");

    mockMvc
        .perform(
            multipart("/api/files")
                .file(new MockMultipartFile("files", "b.txt", "text/plain", new byte[10]))
                .param("path", "a.txt")
                .with(csrf().asHeader()))
        .andExpect(status().isBadRequest());

    assertThat(metric(UPLOAD_SIZE, "COUNT")).isEqualTo(count);
  }

  @Test
  @WithMockUser(username = "testadmin")
  @DisplayName("files served are counted by the route they went out through")
  void filesServedByRoute() throws Exception {
    completeSetup();
    Files.writeString(servingDir.resolve("notes.txt"), "hello");
    Files.createDirectory(servingDir.resolve("folder"));
    double downloads = filesServed("download");
    double previews = filesServed("preview");
    double shared = filesServed("share-link");
    double sharedPreviews = filesServed("share-link-preview");

    mockMvc
        .perform(get("/api/files/download").param("path", "notes.txt"))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/files/download").param("path", "folder")).andExpect(status().isOk());
    mockMvc
        .perform(get("/api/files/preview").param("path", "notes.txt"))
        .andExpect(status().isOk());
    String url =
        json.readTree(
                mockMvc
                    .perform(post("/api/share").param("path", "notes.txt").with(csrf()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("url")
            .asText();
    String link = url.substring(url.indexOf("/share/"));
    mockMvc.perform(get(link)).andExpect(status().isOk());
    mockMvc.perform(get(link + "/download")).andExpect(status().isOk());
    mockMvc.perform(get(link + "/preview")).andExpect(status().isOk());
    // Describing the link for its page serves nothing.
    mockMvc.perform(get(link + "/info")).andExpect(status().isOk());

    assertThat(filesServed("download")).isEqualTo(downloads + 2);
    assertThat(filesServed("preview")).isEqualTo(previews + 1);
    assertThat(filesServed("share-link")).isEqualTo(shared + 2);
    assertThat(filesServed("share-link-preview")).isEqualTo(sharedPreviews + 1);
  }

  @Test
  @WithMockUser(username = "testadmin")
  @DisplayName("a file that is not there, or a dead share link, is not counted as served")
  void missingFilesAreNotCounted() throws Exception {
    completeSetup();
    double downloads = filesServed("download");
    double previews = filesServed("preview");
    double shared = filesServed("share-link");
    double sharedPreviews = filesServed("share-link-preview");

    mockMvc
        .perform(get("/api/files/download").param("path", "nope.txt"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/files/preview").param("path", "nope.txt"))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/share/not-a-real-token")).andExpect(status().isNotFound());
    mockMvc.perform(get("/share/not-a-real-token/preview")).andExpect(status().isNotFound());

    assertThat(filesServed("download")).isEqualTo(downloads);
    assertThat(filesServed("preview")).isEqualTo(previews);
    assertThat(filesServed("share-link")).isEqualTo(shared);
    assertThat(filesServed("share-link-preview")).isEqualTo(sharedPreviews);
  }

  @Test
  @WithMockUser(username = "testadmin")
  @DisplayName("share links are counted when created, and a refused one is not")
  void shareLinksCreated() throws Exception {
    completeSetup();
    Files.writeString(servingDir.resolve("shared.txt"), "share me");
    double created = metric(SHARE_LINKS_CREATED, "COUNT");

    mockMvc
        .perform(post("/api/share").param("path", "shared.txt").with(csrf()))
        .andExpect(status().isOk());
    mockMvc
        .perform(post("/api/share").param("path", "nope.txt").with(csrf()))
        .andExpect(status().isNotFound());

    assertThat(metric(SHARE_LINKS_CREATED, "COUNT")).isEqualTo(created + 1);
  }

  private double filesServed(String route) throws Exception {
    return metric(FILES_SERVED, "COUNT", "route:" + route);
  }

  /** One statistic of a meter, read through the actuator endpoint as a monitoring tool would. */
  private double metric(String name, String statistic, String... tags) throws Exception {
    MockHttpServletRequestBuilder request = get("/actuator/metrics/" + name);
    for (String tag : tags) {
      request.param("tag", tag);
    }
    JsonNode body =
        json.readTree(
            mockMvc
                .perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    for (JsonNode measurement : body.get("measurements")) {
      if (statistic.equals(measurement.get("statistic").asText())) {
        return measurement.get("value").asDouble();
      }
    }
    throw new AssertionError(name + " has no " + statistic + " statistic: " + body);
  }
}
