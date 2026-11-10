package com.javadropbox.javadropbox;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.LocalFileStore;
import com.javadropbox.javadropbox.service.StoragePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Downloads and previews only read. A PDF viewer or the text preview makes a request for every
 * range it reads, and share links are fetched by anyone, so a write on each would be a write on
 * every one of those. While a test here runs, every table refuses inserts, updates and deletes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "owner")
@DisplayName("Downloads and previews do not write to the database")
@TempServingDirectory
class ReadOnlyRequestsIntegrationTests {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;
  @Autowired private StoragePaths storagePaths;
  @Autowired private LocalFileStore localStore;

  private String sharedFile;
  private String sharedFolder;

  @BeforeEach
  void setUp() throws Exception {
    Path home =
        localStore.path(
            storagePaths.home(users.save(new User("owner", "unused", "ROLE_ADMIN"))).key());
    Files.createDirectories(home.resolve("docs"));
    Files.writeString(home.resolve("docs/notes.txt"), "line one\nline two\n");
    sharedFile = share("docs/notes.txt");
    sharedFolder = share("docs");
    refuseWrites();
  }

  @AfterEach
  void tearDown() {
    allowWrites();
    TestDatabase.wipe(jdbc);
  }

  @Test
  @DisplayName("downloading a file, a range of it, or a folder as a zip")
  void downloads() throws Exception {
    mockMvc
        .perform(get("/api/files/download").param("path", "docs/notes.txt"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            get("/api/files/download")
                .param("path", "docs/notes.txt")
                .header(HttpHeaders.RANGE, "bytes=0-3"))
        .andExpect(status().isPartialContent());
    mockMvc.perform(get("/api/files/download").param("path", "docs")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("previewing a file, or a range of it")
  void previews() throws Exception {
    mockMvc
        .perform(get("/api/files/preview").param("path", "docs/notes.txt"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            get("/api/files/preview")
                .param("path", "docs/notes.txt")
                .header(HttpHeaders.RANGE, "bytes=5-"))
        .andExpect(status().isPartialContent());
  }

  @Test
  @DisplayName("downloading and previewing through a share link, without signing in")
  void shareLinks() throws Exception {
    mockMvc.perform(get(sharedFile).with(anonymous())).andExpect(status().isOk());
    mockMvc
        .perform(get(sharedFile).with(anonymous()).header(HttpHeaders.RANGE, "bytes=0-3"))
        .andExpect(status().isPartialContent());
    mockMvc.perform(get(sharedFile + "/preview").with(anonymous())).andExpect(status().isOk());
    mockMvc.perform(get(sharedFolder).with(anonymous())).andExpect(status().isOk());
  }

  private String share(String path) throws Exception {
    String body =
        mockMvc
            .perform(post("/api/share").param("path", path).with(csrf()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String url = json.readTree(body).get("url").asText();
    return url.substring(url.indexOf("/share/"));
  }

  // A statement-level trigger fires even when a statement touches no rows.
  private void refuseWrites() {
    jdbc.execute(
        "CREATE FUNCTION refuse_write() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " RAISE EXCEPTION 'read-only request wrote to %', TG_TABLE_NAME; END $$");
    for (String table : tables()) {
      jdbc.execute(
          "CREATE TRIGGER refuse_write BEFORE INSERT OR UPDATE OR DELETE ON "
              + table
              + " FOR EACH STATEMENT EXECUTE FUNCTION refuse_write()");
    }
  }

  private void allowWrites() {
    jdbc.execute("DROP FUNCTION IF EXISTS refuse_write() CASCADE");
  }

  private List<String> tables() {
    return jdbc.queryForList(
        "SELECT quote_ident(tablename) FROM pg_tables WHERE schemaname = current_schema()"
            + " AND tablename <> 'flyway_schema_history'",
        String.class);
  }
}
