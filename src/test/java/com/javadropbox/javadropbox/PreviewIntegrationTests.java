package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.StoragePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Previews")
class PreviewIntegrationTests {

  private static final String CSP = "Content-Security-Policy";
  private static final String FRAME_OPTIONS = "X-Frame-Options";

  @TempDir static Path servingDir;

  @DynamicPropertySource
  static void overrideServingDirectory(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StoragePaths storagePaths;

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  @BeforeEach
  void setUp() throws IOException {
    Path home = storagePaths.home(users.save(new User("owner", "unused", "ROLE_ADMIN"))).root();
    Files.createDirectories(home.resolve("docs"));
    Files.write(home.resolve("docs/photo.PNG"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
    Files.writeString(home.resolve("docs/report.pdf"), "%PDF-1.7");
    Files.writeString(home.resolve("docs/notes.txt"), "line one\nline two\n");
    Files.writeString(home.resolve("page.html"), "<script>alert(1)</script>");
    Files.writeString(
        home.resolve("logo.svg"),
        "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>");
    Files.write(home.resolve("archive.zip"), new byte[] {'P', 'K', 3, 4});
  }

  @ParameterizedTest(name = "{0} is served inline as {1}")
  @WithMockUser(username = "owner")
  @CsvSource({
    "docs/photo.PNG, image/png",
    "logo.svg, image/svg+xml",
    "docs/report.pdf, application/pdf",
    "docs/notes.txt, text/plain;charset=UTF-8",
  })
  void previewableFilesAreServedInline(String path, String contentType) throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(get("/api/files/preview").param("path", path))
            .andExpect(status().isOk())
            .andExpect(content().contentType(contentType))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andReturn()
            .getResponse();

    ContentDisposition disposition =
        ContentDisposition.parse(response.getHeader(HttpHeaders.CONTENT_DISPOSITION));
    assertThat(disposition.isInline()).isTrue();
    assertThat(disposition.getFilename()).isEqualTo(Path.of(path).getFileName().toString());
  }

  @Test
  @WithMockUser(username = "owner")
  @DisplayName("markup is previewed as plain text, so it shows as source and never runs")
  void markupIsServedAsText() throws Exception {
    mockMvc
        .perform(get("/api/files/preview").param("path", "page.html"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("text/plain;charset=UTF-8"))
        .andExpect(content().string("<script>alert(1)</script>"))
        .andExpect(header().string(CSP, "sandbox"));
  }

  @Test
  @WithMockUser(username = "owner")
  @DisplayName("image and text previews are sandboxed and cannot be framed")
  void imagesAndTextAreSandboxed() throws Exception {
    for (String path : new String[] {"logo.svg", "docs/photo.PNG", "docs/notes.txt"}) {
      mockMvc
          .perform(get("/api/files/preview").param("path", path))
          .andExpect(header().string(CSP, "sandbox"))
          .andExpect(header().stringValues(FRAME_OPTIONS, contains("DENY")));
    }
  }

  // Browsers' PDF viewers will not render in a sandboxed document, so PDFs trade the sandbox for
  // being frameable by the app's own pages, which is how the preview dialog shows them.
  @Test
  @WithMockUser(username = "owner")
  @DisplayName("a PDF preview can be framed by the app itself, and only by it")
  void pdfCanBeFramedBySameOrigin() throws Exception {
    mockMvc
        .perform(get("/api/files/preview").param("path", "docs/report.pdf"))
        .andExpect(header().string(CSP, "frame-ancestors 'self'"))
        .andExpect(header().stringValues(FRAME_OPTIONS, contains("SAMEORIGIN")));
  }

  @Test
  @WithMockUser(username = "owner")
  @DisplayName("downloads of the same PDF still cannot be framed")
  void downloadsStayUnframeable() throws Exception {
    mockMvc
        .perform(get("/api/files/download").param("path", "docs/report.pdf"))
        .andExpect(header().string(CSP, "sandbox"))
        .andExpect(header().stringValues(FRAME_OPTIONS, contains("DENY")));
  }

  @Test
  @WithMockUser(username = "owner")
  @DisplayName("previews support range requests, so a text preview can fetch just the start")
  void previewSupportsRanges() throws Exception {
    mockMvc
        .perform(
            get("/api/files/preview").param("path", "docs/notes.txt").header("Range", "bytes=0-7"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-7/18"))
        .andExpect(content().bytes("line one".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  @WithMockUser(username = "owner")
  @DisplayName("files that cannot be previewed, and folders, are refused")
  void unsupportedItemsAreRefused() throws Exception {
    for (String path : new String[] {"archive.zip", "docs"}) {
      mockMvc
          .perform(get("/api/files/preview").param("path", path))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").isNotEmpty())
          .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));
    }
  }

  @Test
  @WithMockUser(username = "owner")
  @DisplayName("a missing file is a 404")
  void missingFileIsNotFound() throws Exception {
    mockMvc
        .perform(get("/api/files/preview").param("path", "docs/gone.txt"))
        .andExpect(status().isNotFound());
  }

  @ParameterizedTest
  @WithMockUser(username = "owner")
  @ValueSource(strings = {"../outside.txt", "docs/../../outside.txt", "/etc/passwd"})
  @DisplayName("paths outside the serving directory are refused")
  void traversalIsRefused(String path) throws Exception {
    mockMvc
        .perform(get("/api/files/preview").param("path", path))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("previews need a session")
  void previewRequiresAuthentication() throws Exception {
    mockMvc
        .perform(get("/api/files/preview").param("path", "docs/notes.txt"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(username = "owner")
  @DisplayName("the tree says which files can be previewed and how")
  void treeCarriesPreviewType() throws Exception {
    mockMvc
        .perform(get("/api/files"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.name == 'docs')].previewType", contains((Object) null)))
        .andExpect(
            jsonPath("$[?(@.name == 'docs')].children[?(@.name == 'photo.PNG')].previewType")
                .value(contains("image")))
        .andExpect(
            jsonPath("$[?(@.name == 'docs')].children[?(@.name == 'report.pdf')].previewType")
                .value(contains("pdf")))
        .andExpect(jsonPath("$[?(@.name == 'page.html')].previewType").value(contains("text")))
        .andExpect(jsonPath("$[?(@.name == 'archive.zip')].previewType", contains((Object) null)));
  }
}
