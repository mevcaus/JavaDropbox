package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.service.ShareTokenService;
import com.javadropbox.javadropbox.service.StoragePaths;
import io.jsonwebtoken.JwtException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Share token signing key")
class ShareTokenServiceTests {

  // The default that used to be committed to application.properties, and so is public.
  private static final String LEAKED_SECRET = "+mRVZIlDlrnSQTcLyt3WtDhDgkceY5VMzD9D1a5CvyM=";

  @TempDir Path servingDir;

  @Test
  @DisplayName("without a configured secret, a key is generated once and reused after a restart")
  void generatedKeySurvivesRestart() throws Exception {
    StoragePaths paths = new StoragePaths(servingDir.toString());
    ShareTokenService first = new ShareTokenService("", paths);
    String token = first.generateToken("file.txt", 60);

    ShareTokenService afterRestart = new ShareTokenService("", paths);

    assertThat(afterRestart.resolvePath(token)).isEqualTo("file.txt");
    assertThat(paths.internalDir().resolve("share-jwt.key")).exists();
  }

  @Test
  @DisplayName("separate installs generate different keys")
  void installsDoNotShareAKey(@TempDir Path otherServingDir) throws Exception {
    String token =
        new ShareTokenService("", new StoragePaths(servingDir.toString()))
            .generateToken("file.txt", 60);
    ShareTokenService otherInstall =
        new ShareTokenService("", new StoragePaths(otherServingDir.toString()));

    assertThatThrownBy(() -> otherInstall.resolvePath(token)).isInstanceOf(JwtException.class);
  }

  @Test
  @DisplayName("a token forged with the formerly committed secret is rejected")
  void leakedSecretNoLongerWorks() throws Exception {
    StoragePaths paths = new StoragePaths(servingDir.toString());
    String forged = new ShareTokenService(LEAKED_SECRET, paths).generateToken("", 60);

    ShareTokenService install = new ShareTokenService("", paths);

    assertThatThrownBy(() -> install.resolvePath(forged)).isInstanceOf(JwtException.class);
  }

  @Test
  @DisplayName("a configured secret takes precedence over the generated key")
  void configuredSecretIsUsed(@TempDir Path otherServingDir) throws Exception {
    String secret = "dGVzdC1vbmx5LXNlY3JldC1kby1ub3QtdXNlLWluLXByb2QtMTIzNDU2Nzg=";
    String token =
        new ShareTokenService(secret, new StoragePaths(servingDir.toString()))
            .generateToken("file.txt", 60);

    ShareTokenService other =
        new ShareTokenService(secret, new StoragePaths(otherServingDir.toString()));

    assertThat(other.resolvePath(token)).isEqualTo("file.txt");
  }
}
