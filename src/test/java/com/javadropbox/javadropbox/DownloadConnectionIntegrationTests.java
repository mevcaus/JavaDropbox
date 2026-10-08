package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.ShareLinkService;
import com.javadropbox.javadropbox.service.StoragePaths;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A download in progress must not keep a database connection: with open-in-view on, Hibernate holds
 * the one a request first used until the response is finished, so a few slow clients could take the
 * whole pool. The pool here has a single connection, so a second request can only succeed if the
 * paused download has given it back.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.hikari.maximum-pool-size=1",
      "spring.datasource.hikari.connection-timeout=1500",
      // On PostgreSQL, Flyway migrates on two connections at once: one holds the lock that keeps
      // two instances from migrating together, the other runs the migrations. A pool of one cannot
      // give it both, so it connects by itself, to the same database, rather than through the pool.
      "spring.flyway.user=${spring.datasource.username}",
      "spring.flyway.password=${spring.datasource.password}"
    })
// Signed in as the owner, who makes the share links the downloads go through.
@WithMockUser(username = "owner")
@DisplayName("Database connections during downloads")
class DownloadConnectionIntegrationTests {

  @TempDir static Path servingDir;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
    MainProperties.register(registry, "spring.jpa.open-in-view");
  }

  @LocalServerPort private int port;

  @Autowired private UserRepository users;
  @Autowired private ShareLinkService shareLinks;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StoragePaths storagePaths;

  @BeforeEach
  void setUp() throws IOException {
    Path home = storagePaths.home(users.save(new User("owner", "unused", "ROLE_ADMIN"))).root();
    // Sparse, so it costs no disk, but far more than the socket buffers can absorb.
    try (RandomAccessFile big = new RandomAccessFile(home.resolve("big.bin").toFile(), "rw")) {
      big.setLength(512L * 1024 * 1024);
    }
    Files.writeString(home.resolve("small.txt"), "small");
  }

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  @Test
  @DisplayName("a paused download leaves the connection free for other requests")
  void pausedDownloadDoesNotHoldTheConnection() throws Exception {
    HttpTestClient http = new HttpTestClient(port);
    String small = "/share/" + shareLinks.create("small.txt", Duration.ofHours(1)).token();
    // Also lets the setup filter see that an account exists, so it stops asking the database.
    assertThat(http.get(small, Map.of()).statusCode()).isEqualTo(200);

    try (Socket slow = new Socket("localhost", port)) {
      slow.setReceiveBufferSize(4096);
      OutputStream out = slow.getOutputStream();
      String path = "/share/" + shareLinks.create("big.bin", Duration.ofHours(1)).token();
      out.write(
          ("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      InputStream in = slow.getInputStream();
      // The headers and the start of the body, then stop reading so the server blocks mid-write.
      in.readNBytes(8192);
      Thread.sleep(500);

      assertThat(http.get(small, Map.of()).statusCode()).isEqualTo(200);
    }
  }
}
