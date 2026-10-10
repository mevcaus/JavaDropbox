package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.LocalFileStore;
import com.javadropbox.javadropbox.service.ShareLinkService;
import com.javadropbox.javadropbox.service.StoragePaths;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Clients that cancel a download part-way, over a real socket. Cancelling is routine, so it must
 * not be logged as a server error, and nothing may try to write an error body into the half-sent
 * response.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "logging.level.com.javadropbox.javadropbox.controller.ApiExceptionHandler=DEBUG")
@ExtendWith(OutputCaptureExtension.class)
// Signed in as the owner, who makes the share links the downloads go through.
@WithMockUser(username = "owner")
@DisplayName("Cancelled downloads")
class ClientAbortIntegrationTests {

  private static final String HANDLED = "Client went away";

  @TempDir static Path servingDir;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
  }

  @LocalServerPort private int port;

  @Autowired private UserRepository users;
  @Autowired private ShareLinkService shareLinks;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StoragePaths storagePaths;
  @Autowired private LocalFileStore localStore;

  @BeforeEach
  void setUp() throws IOException {
    Path home =
        localStore.path(
            storagePaths.home(users.save(new User("owner", "unused", "ROLE_ADMIN"))).key());
    // Sparse, so it costs no disk, but far more than the socket buffers can absorb.
    try (RandomAccessFile big = new RandomAccessFile(home.resolve("big.bin").toFile(), "rw")) {
      big.setLength(256L * 1024 * 1024);
    }
    // Random, so the zip cannot compress it into something the buffers could absorb.
    byte[] noise = new byte[16 * 1024 * 1024];
    new Random(1).nextBytes(noise);
    Files.createDirectories(home.resolve("folder"));
    Files.write(home.resolve("folder/noise.bin"), noise);
  }

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  @Test
  @DisplayName("a cancelled file download is logged at debug only")
  void cancelledFileDownload(CapturedOutput output) throws Exception {
    String log = startAndCancel("big.bin", output);

    assertNothingAboveDebug(log);
  }

  @Test
  @DisplayName("a cancelled folder download is logged at debug only")
  void cancelledFolderDownload(CapturedOutput output) throws Exception {
    String log = startAndCancel("folder", output);

    assertNothingAboveDebug(log);
  }

  /**
   * Starts a share-link download, reads a little of it, and resets the connection.
   *
   * @return what the server logged meanwhile
   */
  private String startAndCancel(String path, CapturedOutput output) throws Exception {
    int before = output.getOut().length();
    try (Socket socket = new Socket("localhost", port)) {
      OutputStream out = socket.getOutputStream();
      String target = "/share/" + shareLinks.create(path, Duration.ofHours(1)).token();
      out.write(
          ("GET " + target + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      socket.getInputStream().readNBytes(64 * 1024);
      // A reset rather than an orderly close, like a browser dropping a cancelled download.
      socket.setSoLinger(true, 0);
    }
    // Wait until the server has handled the failed write one way or the other, then a little
    // longer for anything it logs after that.
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () -> {
              String since = output.getOut().substring(before);
              return since.contains(HANDLED) || since.contains("File operation failed");
            });
    Thread.sleep(300);
    return output.getOut().substring(before);
  }

  private static void assertNothingAboveDebug(String log) {
    assertThat(log).contains(HANDLED);
    assertThat(log.lines())
        .noneMatch(line -> line.contains(" ERROR ") || line.contains(" WARN  "))
        .noneMatch(line -> line.contains("ClientAbortException"));
  }
}
