package com.javadropbox.javadropbox.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Signs and validates the JWTs used by public share links. The token's only claim is the relative
 * path being shared, so anyone holding a valid token can download that one path until it expires
 * &mdash; no server-side state is kept.
 *
 * <p>The signing key comes from {@code app.share.jwt-secret} when it is set. Otherwise a random key
 * is generated on first start and kept in the app's internal directory, so links survive restarts
 * without any configuration and no two installs ever share a key.
 */
@Service
public class ShareTokenService {

  static final String KEY_FILE = "share-jwt.key";

  private static final String PATH_CLAIM = "path";
  private static final int KEY_BYTES = 32;

  private static final Logger log = LoggerFactory.getLogger(ShareTokenService.class);

  private final SecretKey signingKey;

  public ShareTokenService(
      @Value("${app.share.jwt-secret:}") String secret, StoragePaths storagePaths)
      throws IOException {
    byte[] key =
        secret.isBlank()
            ? loadOrCreateKey(storagePaths.internalDir().resolve(KEY_FILE))
            : Base64.getDecoder().decode(secret.trim());
    this.signingKey = Keys.hmacShaKeyFor(key);
  }

  private static byte[] loadOrCreateKey(Path keyFile) throws IOException {
    if (Files.exists(keyFile)) {
      return Base64.getDecoder().decode(Files.readString(keyFile).trim());
    }

    byte[] key = new byte[KEY_BYTES];
    new SecureRandom().nextBytes(key);
    Files.createDirectories(keyFile.getParent());
    try {
      Files.writeString(
          keyFile, Base64.getEncoder().encodeToString(key), StandardOpenOption.CREATE_NEW);
    } catch (FileAlreadyExistsException e) {
      // Another instance won the race; use its key so both sign the same way.
      return Base64.getDecoder().decode(Files.readString(keyFile).trim());
    }
    restrictToOwner(keyFile);
    log.info("Generated a share-link signing key at {}", keyFile);
    return key;
  }

  private static void restrictToOwner(Path file) {
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException | IOException e) {
      log.debug("Could not restrict permissions on {}", file, e);
    }
  }

  public String generateToken(String relativePath, long expirationMinutes) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + Duration.ofMinutes(expirationMinutes).toMillis());

    return Jwts.builder()
        .claim(PATH_CLAIM, relativePath)
        .issuedAt(now)
        .expiration(expiry)
        .signWith(signingKey)
        .compact();
  }

  /**
   * @throws JwtException if the token is malformed, tampered with, or expired
   */
  public String resolvePath(String token) throws JwtException {
    Claims claims =
        Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();

    return claims.get(PATH_CLAIM, String.class);
  }
}
