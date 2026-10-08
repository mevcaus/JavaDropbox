package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.StoragePaths;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * An anonymous upload that fails the CSRF check must be refused before its body is parsed:
 * otherwise anyone can make the server write up to the upload limit to its temp folder. Parts are
 * spooled to a folder of the test's own with no in-memory threshold, so any parsing at all shows up
 * there, and the upload is sent slowly enough for a watcher to see it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.servlet.multipart.file-size-threshold=0")
@DisplayName("Multipart uploads and the CSRF check")
class MultipartCsrfIntegrationTests {

  private static final int MB = 1024 * 1024;

  @TempDir static Path servingDir;

  @TempDir static Path spoolDir;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
    registry.add("spring.servlet.multipart.location", () -> spoolDir.toString());
    MainProperties.register(
        registry,
        "spring.servlet.multipart.max-file-size",
        "spring.servlet.multipart.max-request-size",
        "spring.servlet.multipart.resolve-lazily");
  }

  @LocalServerPort private int port;

  @Autowired private UserRepository userRepository;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private StoragePaths storagePaths;

  private User owner;
  // The signed-in account's folder, where its files are.
  private Path home;

  @BeforeEach
  void setUp() {
    owner =
        userRepository
            .findByUsername("owner")
            .orElseGet(() -> userRepository.save(new User("owner", "unused", "ROLE_ADMIN")));
    home = storagePaths.home(owner).root();
  }

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  @Test
  @DisplayName("an upload without a CSRF header writes nothing to disk")
  void missingHeaderSpoolsNothing() throws Exception {
    Upload upload = upload("");

    assertThat(upload.mostSpooled()).isZero();
    assertThat(upload.status()).isIn("403", null);
  }

  @Test
  @DisplayName("an upload with a wrong CSRF header writes nothing to disk")
  void wrongHeaderSpoolsNothing() throws Exception {
    Upload upload = upload("X-XSRF-TOKEN: wrong\r\n");

    assertThat(upload.mostSpooled()).isZero();
    assertThat(upload.status()).isIn("403", null);
  }

  @Test
  @DisplayName("an anonymous upload with a valid CSRF token writes nothing to disk")
  void anonymousUploadSpoolsNothing() throws Exception {
    // Anyone can get a valid token: every response hands one out.
    String token = new HttpTestClient(port).csrfToken();
    Upload upload = upload("Cookie: XSRF-TOKEN=" + token + "\r\nX-XSRF-TOKEN: " + token + "\r\n");

    assertThat(upload.mostSpooled()).isZero();
    assertThat(upload.status()).isIn("401", null);
  }

  /**
   * The status code, or null when the server closed the connection before the client could read it
   * (it may, rather than read the rest of a body it has refused), and the most bytes seen in the
   * spool folder at any moment.
   */
  private record Upload(String status, long mostSpooled) {}

  /** POSTs a 16 MB file to /api/files, pausing half-way, with {@code headers} added. */
  private Upload upload(String headers) throws Exception {
    String boundary = "----upload";
    byte[] head =
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"big.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
    byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
    byte[] chunk = new byte[MB];
    int chunks = 16;

    AtomicLong mostSpooled = new AtomicLong();
    AtomicBoolean done = new AtomicBoolean();
    Thread watcher =
        Thread.ofPlatform()
            .start(
                () -> {
                  while (!done.get()) {
                    mostSpooled.accumulateAndGet(spooledBytes(), Math::max);
                    LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
                  }
                });

    String status;
    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(20_000);
      OutputStream out = socket.getOutputStream();
      try {
        out.write(
            ("POST /api/files HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Type: multipart/form-data; boundary="
                    + boundary
                    + "\r\nContent-Length: "
                    + (head.length + (long) chunks * MB + tail.length)
                    + "\r\n"
                    + headers
                    + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(head);
        for (int i = 0; i < chunks; i++) {
          out.write(chunk);
          out.flush();
          if (i == chunks / 2) {
            // Anything parsing the body is now blocked waiting for the rest, with what it has
            // read so far on disk.
            Thread.sleep(1000);
          }
        }
        out.write(tail);
        out.flush();
      } catch (IOException refused) {
        // The server answered and closed the connection without reading the whole body.
      }
      status = readStatus(socket.getInputStream());
    } finally {
      done.set(true);
      watcher.join();
    }
    return new Upload(status, mostSpooled.get());
  }

  private static String readStatus(InputStream in) {
    try {
      String statusLine = new String(in.readNBytes(12), StandardCharsets.US_ASCII);
      return statusLine.startsWith("HTTP/1.1 ") ? statusLine.substring(9, 12) : null;
    } catch (IOException closed) {
      return null;
    }
  }

  private static long spooledBytes() {
    try (Stream<Path> files = Files.list(spoolDir)) {
      return files.mapToLong(MultipartCsrfIntegrationTests::sizeOrZero).sum();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // A spooled part can be deleted between listing the folder and asking for its size.
  private static long sizeOrZero(Path file) {
    try {
      return Files.size(file);
    } catch (IOException gone) {
      return 0;
    }
  }
}
