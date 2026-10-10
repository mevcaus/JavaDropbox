package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Share links used to be JWTs signed with a key from {@code app.share.jwt-secret} or a generated
 * {@code .javadropbox/share-jwt.key}. Links are stored on the server now, so no key may exist: one
 * in the served folder could mint links, and a broken one used to stop the app from starting.
 */
@DisplayName("Retired share-link signing key")
class RetiredShareKeyTests {

  // The default that used to be committed to application.properties, and so is public.
  private static final String LEAKED_SECRET = "+mRVZIlDlrnSQTcLyt3WtDhDgkceY5VMzD9D1a5CvyM=";

  @TempDir Path servingDir;

  @Test
  @DisplayName("no signing key is created in the served folder")
  void noKeyIsCreated() {
    try (ConfigurableApplicationContext app = start()) {
      assertThat(keyFile()).doesNotExist();
    }
  }

  @Test
  @DisplayName("a key file left by an earlier version is deleted at startup, even an empty one")
  void leftoverKeyFileIsDeleted() throws Exception {
    // What a crash between creating and writing the key used to leave behind.
    Files.createDirectories(keyFile().getParent());
    Files.writeString(keyFile(), "");

    try (ConfigurableApplicationContext app = start()) {
      assertThat(keyFile()).doesNotExist();
    }
  }

  @Test
  @DisplayName("an empty secret setting, e.g. passed through by a compose file, is ignored")
  void emptySecretIsIgnored() {
    try (ConfigurableApplicationContext app = start("--app.share.jwt-secret=")) {
      assertThat(keyFile()).doesNotExist();
    }
  }

  @Test
  @DisplayName("a configured secret stops startup and says it can be removed")
  void configuredSecretFailsStartup() {
    assertThatThrownBy(() -> start("--app.share.jwt-secret=" + LEAKED_SECRET).close())
        .rootCause()
        .hasMessageContaining("app.share.jwt-secret")
        .hasMessageContaining("APP_SHARE_JWT_SECRET")
        .hasMessageContaining("stored on the server")
        .hasMessageContaining("remove");
  }

  private Path keyFile() {
    return servingDir.resolve(".javadropbox/share-jwt.key");
  }

  // The whole application, as it starts in production, on a database of its own (see
  // TestDatabaseEnvironment) so it does not disturb the contexts other tests share.
  private ConfigurableApplicationContext start(String... extraArgs) {
    List<String> args = new ArrayList<>();
    args.add("--server.port=0");
    args.add("--javadropbox.serving.directory=" + servingDir);
    args.addAll(List.of(extraArgs));
    return new SpringApplicationBuilder(JavadropboxApplication.class)
        .run(args.toArray(String[]::new));
  }
}
