package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.ShareTokenService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Downloads")
class DownloadIntegrationTests {

  @TempDir static Path servingDir;
  @TempDir static Path outsideDir;

  @DynamicPropertySource
  static void overrideServingDirectory(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private ShareTokenService shareTokens;

  @BeforeEach
  void setUp() throws IOException {
    if (users.count() == 0) {
      users.save(new User("owner", "unused", "ROLE_ADMIN"));
    }
    Files.createDirectories(servingDir.resolve("docs/nested"));
    Files.writeString(servingDir.resolve("docs/top.txt"), "top");
    Files.writeString(servingDir.resolve("docs/nested/deep.txt"), "deep");
    Files.writeString(outsideDir.resolve("secret.txt"), "secret");
    Path link = servingDir.resolve("docs/escape");
    if (!Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      Files.createSymbolicLink(link, outsideDir);
    }
  }

  @Test
  @WithMockUser(username = "owner")
  @DisplayName("a folder downloads as a zip of its contents, without following symlinks")
  void folderDownloadsAsZip() throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(get("/api/download").param("path", "docs"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/zip"))
            .andReturn()
            .getResponse();

    assertThat(filename(response)).isEqualTo("docs.zip");
    assertThat(unzip(response.getContentAsByteArray()))
        .containsOnly(Map.entry("docs/top.txt", "top"), Map.entry("docs/nested/deep.txt", "deep"));
  }

  @Test
  @DisplayName("a share link to a folder streams the zip without signing in")
  void sharedFolderDownloads() throws Exception {
    String token = shareTokens.generateToken("docs", 60);

    MockHttpServletResponse response =
        mockMvc
            .perform(get("/share/" + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();

    assertThat(unzip(response.getContentAsByteArray())).containsKey("docs/nested/deep.txt");
  }

  @Test
  @WithMockUser(username = "owner")
  @DisplayName("filenames with quotes and non-ASCII characters survive the header intact")
  void awkwardFilenameIsEncoded() throws Exception {
    String name = "we\"ird; name ü.txt";
    Files.writeString(servingDir.resolve(name), "x");

    MockHttpServletResponse response =
        mockMvc
            .perform(get("/api/download").param("path", name))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();

    assertThat(filename(response)).isEqualTo(name);
  }

  @Test
  @WithMockUser(username = "owner")
  @DisplayName("file downloads support range requests, so they can be resumed")
  void fileDownloadSupportsRanges() throws Exception {
    mockMvc
        .perform(get("/api/download").param("path", "docs/top.txt").header("Range", "bytes=1-2"))
        .andExpect(status().isPartialContent())
        .andExpect(content().string("op"));
  }

  @Test
  @DisplayName("a share link to something since deleted is a 404")
  void sharedMissingFileIsNotFound() throws Exception {
    String token = shareTokens.generateToken("gone.txt", 60);

    mockMvc.perform(get("/share/" + token)).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("a share link naming the root, e.g. minted with the old public key, is a 404")
  void sharedRootIsNotFound() throws Exception {
    String token = shareTokens.generateToken("", 60);

    mockMvc
        .perform(get("/share/" + token))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));
  }

  private static String filename(MockHttpServletResponse response) {
    return ContentDisposition.parse(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
        .getFilename();
  }

  private static Map<String, String> unzip(byte[] zip) throws IOException {
    Map<String, String> entries = new HashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
        entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return entries;
  }
}
