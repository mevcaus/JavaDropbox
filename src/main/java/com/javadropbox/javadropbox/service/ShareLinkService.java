package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.Download;
import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.ShareLink;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.ShareLinkRepository;
import com.javadropbox.javadropbox.service.StoragePaths.StoragePath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public share links. Each link is a row bound to the metadata row of the item it was made for; the
 * URL carries a random token and only the token's SHA-256 hash is stored, so neither the URL nor
 * the database reveals or rebuilds anything usable.
 */
@Service
public class ShareLinkService {

  private static final int TOKEN_BYTES = 32;

  private final ShareLinkRepository links;
  private final FileMetadataRepository files;
  private final StoragePaths storagePaths;
  private final FileService fileService;
  private final AuthService authService;
  private final SecureRandom random = new SecureRandom();

  public ShareLinkService(
      ShareLinkRepository links,
      FileMetadataRepository files,
      StoragePaths storagePaths,
      FileService fileService,
      AuthService authService) {
    this.links = links;
    this.files = files;
    this.storagePaths = storagePaths;
    this.fileService = fileService;
    this.authService = authService;
  }

  /** A newly created link. The token is only ever available here, when the link is made. */
  public record CreatedLink(String token, Instant expiresAt) {}

  /**
   * Creates a link to the file or folder at {@code path}. An item copied in by hand, which has no
   * metadata row yet, is given one so the link has something to belong to.
   *
   * @throws NotFoundException if nothing is there
   */
  @Transactional
  public CreatedLink create(String path, Duration lifetime) throws IOException {
    StoragePath target = storagePaths.resolveItem(path);
    if (!Files.exists(target.path())) {
      throw new NotFoundException("Not found: " + target.key());
    }
    FileMetadata file = files.findByPath(target.key()).orElse(null);
    if (file == null) {
      boolean isDirectory = Files.isDirectory(target.path());
      long size = isDirectory ? 0 : Files.size(target.path());
      file =
          files.save(
              new FileMetadata(
                  target.key(), target.name(), size, isDirectory, authService.currentUser()));
    }

    String token = newToken();
    Instant now = Instant.now();
    Instant expiresAt = now.plus(lifetime);
    links.save(new ShareLink(hash(token), file, now, expiresAt, authService.currentUser()));
    // Expired links can never open again; dropping them here keeps the table to about a week's.
    links.deleteExpiredBefore(now);
    return new CreatedLink(token, expiresAt);
  }

  /**
   * What a link's token opens.
   *
   * @throws NotFoundException if the token is unknown, expired or revoked, or the item the link was
   *     made for is gone, has moved, or has changed between file and folder
   */
  public Download open(String token) throws IOException {
    ShareLink link =
        links
            .findByTokenHash(hash(token))
            .filter(l -> l.isActive(Instant.now()))
            .orElseThrow(ShareLinkService::linkNotFound);

    FileMetadata file = link.getFile();
    if (!file.getPath().equals(link.getPath())) {
      throw linkNotFound();
    }
    StoragePath target = storagePaths.resolveItem(link.getPath());
    if (Files.isDirectory(target.path()) != Boolean.TRUE.equals(file.getIsDirectory())) {
      throw linkNotFound();
    }
    return fileService.download(link.getPath());
  }

  private String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String hash(String token) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java runtime provides SHA-256", e);
    }
  }

  private static NotFoundException linkNotFound() {
    return new NotFoundException("Share link not found");
  }
}
