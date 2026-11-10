package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.LocalFileStore;
import com.javadropbox.javadropbox.service.StoragePaths;
import jakarta.servlet.MultipartConfigElement;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Tomcat spools each upload to disk before the app copies it into the store. That has to happen in
 * the serving directory, which the Docker image keeps on its volume, and not in {@code
 * java.io.tmpdir}, which there is the container's own writable layer. Runs on a real Tomcat, since
 * MockMvc parses multipart requests itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Where uploads are spooled")
@TempServingDirectory
class UploadSpoolIntegrationTests {

  private static final int MB = 1024 * 1024;
  private static final int CHUNKS = 8;

  @Value("${javadropbox.serving.directory}")
  private Path servingDir;

  @LocalServerPort private int port;

  @Autowired private MultipartConfigElement multipartConfig;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StoragePaths storagePaths;
  @Autowired private LocalFileStore localStore;

  @AfterEach
  void tearDown() throws IOException {
    TestDatabase.wipe(jdbc);
    TestStorage.empty(servingDir);
    // Back as startup left it, for the next test.
    Files.createDirectories(Path.of(multipartConfig.getLocation()));
  }

  @Test
  @DisplayName(
      "the spool folder is .javadropbox/uploads in the serving directory, created at startup")
  void spoolIsInTheServingDirectory() throws IOException {
    Path spool = Path.of(multipartConfig.getLocation());

    assertThat(spool).isDirectory();
    assertThat(spool.toRealPath())
        .isEqualTo(servingDir.toRealPath().resolve(".javadropbox").resolve("uploads"));
  }

  @Test
  @DisplayName("an upload is spooled there while it arrives, then stored, leaving nothing behind")
  void uploadIsSpooledInTheServingDirectory() throws Exception {
    Path spool = Path.of(multipartConfig.getLocation());
    assertThat(spool).isDirectory();

    uploadAndCheck(spool);
  }

  @Test
  @DisplayName("a spool folder deleted while the app runs is made again by the next upload")
  void deletedSpoolIsRecreated() throws Exception {
    // As when .javadropbox is deleted to have the search index rebuilt.
    Path spool = Path.of(multipartConfig.getLocation());
    Files.delete(spool);

    uploadAndCheck(spool);
  }

  private void uploadAndCheck(Path spool) throws Exception {
    User owner =
        users.save(new User("owner", passwordEncoder.encode("correct-horse"), "ROLE_ADMIN"));
    HttpTestClient http = new HttpTestClient(port);
    HttpResponse<String> login = http.login("owner", "correct-horse", Map.of());
    assertThat(login.statusCode()).isEqualTo(200);
    String session = HttpTestClient.cookieValue(login, "JSESSIONID");
    String token = http.csrfToken();

    Upload upload =
        upload(
            spool,
            "Cookie: JSESSIONID="
                + session
                + "; XSRF-TOKEN="
                + token
                + "\r\nX-XSRF-TOKEN: "
                + token
                + "\r\n");

    assertThat(upload.status()).isEqualTo("200");
    // Tomcat had read what arrived before the pause, and written it there.
    assertThat(upload.mostSpooled()).isGreaterThanOrEqualTo(MB);
    assertThat(localStore.path(storagePaths.home(owner).key()).resolve("big.bin"))
        .hasSize((long) CHUNKS * MB);
    assertThat(spooledBytes(spool)).isZero();
  }

  private record Upload(String status, long mostSpooled) {}

  /** POSTs an 8 MB file to /api/files, pausing half-way, while watching {@code spool}. */
  private Upload upload(Path spool, String headers) throws Exception {
    String boundary = "----upload";
    byte[] head =
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"big.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
    byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
    byte[] chunk = new byte[MB];

    AtomicLong mostSpooled = new AtomicLong();
    AtomicBoolean done = new AtomicBoolean();
    Thread watcher =
        Thread.ofPlatform()
            .start(
                () -> {
                  while (!done.get()) {
                    mostSpooled.accumulateAndGet(spooledBytes(spool), Math::max);
                    LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
                  }
                });

    String status;
    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(20_000);
      OutputStream out = socket.getOutputStream();
      try {
        out.write(
            ("POST /api/files HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                    + "Content-Type: multipart/form-data; boundary="
                    + boundary
                    + "\r\nContent-Length: "
                    + (head.length + (long) CHUNKS * MB + tail.length)
                    + "\r\n"
                    + headers
                    + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(head);
        for (int i = 0; i < CHUNKS; i++) {
          out.write(chunk);
          out.flush();
          if (i == CHUNKS / 2 - 1) {
            // Tomcat is now waiting for the rest, with the first half on disk.
            Thread.sleep(1000);
          }
        }
        out.write(tail);
        out.flush();
        status = readStatus(socket.getInputStream());
      } finally {
        done.set(true);
        watcher.join();
      }
    }
    return new Upload(status, mostSpooled.get());
  }

  private static String readStatus(InputStream in) throws IOException {
    String statusLine = new String(in.readNBytes(12), StandardCharsets.US_ASCII);
    return statusLine.startsWith("HTTP/1.1 ") ? statusLine.substring(9, 12) : statusLine;
  }

  // Nothing is spooled while the folder is missing, before Tomcat makes it again.
  private static long spooledBytes(Path spool) {
    try (Stream<Path> files = Files.list(spool)) {
      return files.mapToLong(UploadSpoolIntegrationTests::sizeOrZero).sum();
    } catch (NoSuchFileException missing) {
      return 0;
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
