package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.ImportService;
import com.javadropbox.javadropbox.service.LocalFileStore;
import com.javadropbox.javadropbox.service.StoragePaths;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Downloads and previews only read. A PDF viewer or the text preview makes a request for every
 * range it reads, and share links are fetched by anyone, so a write on each would be a write on
 * every one of those. While a test here runs, every table refuses inserts, updates and deletes.
 *
 * <p>But one: a signed-in request records when its session was last used, so that it expires after
 * being idle whichever instance served it. That is an update of its one spring_session row, by key.
 * The requests go over real HTTP with a session signed in through the login form, as a browser's
 * are: MockMvc's test users would keep a session of their own, written on every request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Downloads and previews do not write to the database")
@TempServingDirectory
class ReadOnlyRequestsIntegrationTests {

  @LocalServerPort private int port;

  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;
  @Autowired private StoragePaths storagePaths;
  @Autowired private LocalFileStore localStore;
  @Autowired private ImportService imports;

  private HttpTestClient http;
  private String sessionCookie;
  private String sharedFile;
  private String sharedFolder;

  @BeforeEach
  void setUp() throws Exception {
    http = new HttpTestClient(port);
    User owner = users.save(new User("owner", passwordEncoder.encode("pw"), "ROLE_ADMIN"));
    Path home = localStore.path(storagePaths.home(owner).key());
    Files.createDirectories(home.resolve("docs"));
    Files.writeString(home.resolve("docs/notes.txt"), "line one\nline two\n");
    sessionCookie =
        "JSESSIONID="
            + HttpTestClient.cookieValue(http.login("owner", "pw", Map.of()), "JSESSIONID");
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
  void downloads() {
    assertStatus(signedIn("/api/files/download?path=docs/notes.txt", null), 200);
    assertStatus(signedIn("/api/files/download?path=docs/notes.txt", "bytes=0-3"), 206);
    assertStatus(signedIn("/api/files/download?path=docs", null), 200);
  }

  @Test
  @DisplayName("previewing a file, or a range of it")
  void previews() {
    assertStatus(signedIn("/api/files/preview?path=docs/notes.txt", null), 200);
    assertStatus(signedIn("/api/files/preview?path=docs/notes.txt", "bytes=5-"), 206);
  }

  @Test
  @DisplayName("downloading and previewing through a share link, without signing in")
  void shareLinks() {
    assertStatus(http.get(sharedFile, Map.of()), 200);
    assertStatus(http.get(sharedFile, Map.of(HttpHeaders.RANGE, "bytes=0-3")), 206);
    assertStatus(http.get(sharedFile + "/preview", Map.of()), 200);
    assertStatus(http.get(sharedFolder, Map.of()), 200);
  }

  private HttpResponse<String> signedIn(String path, String range) {
    Map<String, String> headers = new HashMap<>(Map.of("Cookie", sessionCookie));
    if (range != null) {
      headers.put(HttpHeaders.RANGE, range);
    }
    return http.get(path, headers);
  }

  private static void assertStatus(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
  }

  // Files written to disk here are copied in by hand, so they are shared once imported.
  private String share(String path) throws Exception {
    imports.importFiles(users.findByUsername("owner").orElseThrow().getId(), "test");
    HttpResponse<String> response =
        http.postForm(
            "/api/share", Map.of("path", path), http.withCsrf(Map.of("Cookie", sessionCookie)));
    assertStatus(response, 200);
    String url = json.readTree(response.body()).get("url").asText();
    return url.substring(url.indexOf("/share/"));
  }

  // A statement-level trigger fires even when a statement touches no rows.
  private void refuseWrites() {
    jdbc.execute(
        "CREATE FUNCTION refuse_write() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " RAISE EXCEPTION 'read-only request wrote to %', TG_TABLE_NAME; END $$");
    for (String table : tables()) {
      // All but the update that records a session's last use.
      String writes =
          table.equals("spring_session") ? "INSERT OR DELETE" : "INSERT OR UPDATE OR DELETE";
      jdbc.execute(
          "CREATE TRIGGER refuse_write BEFORE "
              + writes
              + " ON "
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
