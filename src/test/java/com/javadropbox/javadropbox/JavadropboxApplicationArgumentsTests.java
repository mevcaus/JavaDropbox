package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Command-line directory shorthands")
class JavadropboxApplicationArgumentsTests {

  @Test
  @DisplayName("--directory= becomes the serving directory property, wherever it appears")
  void directoryFlagAnywhere() {
    assertThat(
            JavadropboxApplication.withDirectoryShorthand(
                new String[] {"--server.port=9000", "--directory=/data"}))
        .containsExactly("--server.port=9000", "--javadropbox.serving.directory=/data");
  }

  @Test
  @DisplayName("a bare first argument is taken as the directory")
  void barePath() {
    assertThat(JavadropboxApplication.withDirectoryShorthand(new String[] {"/srv/files"}))
        .containsExactly("--javadropbox.serving.directory=/srv/files");
  }

  @Test
  @DisplayName("other arguments are left alone")
  void otherArgumentsUntouched() {
    assertThat(JavadropboxApplication.withDirectoryShorthand(new String[] {"--debug"}))
        .containsExactly("--debug");
  }
}
