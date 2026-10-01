package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.service.StoragePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cleans up after the share-link signing key that earlier versions used. Links were JWTs signed
 * with {@code app.share.jwt-secret} or with a key generated into {@code
 * .javadropbox/share-jwt.key}; they are stored on the server now and nothing is signed.
 *
 * <ul>
 *   <li>A configured secret stops startup: it no longer does anything, and whoever set it should
 *       hear so rather than believe it still protects links.
 *   <li>A leftover key file is deleted. It sits inside the served folder, so anything else that
 *       exposes that folder would expose it.
 * </ul>
 */
@Component
public class RetiredShareKey {

  static final String KEY_FILE = "share-jwt.key";

  private static final Logger log = LoggerFactory.getLogger(RetiredShareKey.class);

  public RetiredShareKey(
      @Value("${app.share.jwt-secret:}") String secret, StoragePaths storagePaths) {
    if (!secret.isBlank()) {
      throw new IllegalStateException(
          "app.share.jwt-secret (APP_SHARE_JWT_SECRET) is set, but share links are now stored on"
              + " the server and no longer signed, so there is no key to configure and the setting"
              + " can be removed. Remove it and start again.");
    }
    deleteLeftoverKey(storagePaths.internalDir().resolve(KEY_FILE));
  }

  private static void deleteLeftoverKey(Path keyFile) {
    try {
      if (Files.deleteIfExists(keyFile)) {
        log.info(
            "Deleted the old share-link signing key {}: links are stored on the server now",
            keyFile);
      }
    } catch (IOException e) {
      log.warn("Could not delete the old share-link signing key {}; delete it by hand", keyFile, e);
    }
  }
}
