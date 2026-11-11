package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.Download;
import com.javadropbox.javadropbox.dto.Preview;
import com.javadropbox.javadropbox.dto.ShareLinkDto;
import com.javadropbox.javadropbox.dto.SharedItemDto;
import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.PreviewType;
import com.javadropbox.javadropbox.model.ShareLink;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.ShareLinkRepository;
import com.javadropbox.javadropbox.service.FileStore.Entry;
import com.javadropbox.javadropbox.service.StoragePaths.StoragePath;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public share links to items in their creator's folder. Each link is a row bound to the metadata
 * row of the item it was made for; the URL carries a random token and only the token's SHA-256 hash
 * is stored, so neither the URL nor the database reveals or rebuilds anything usable.
 */
@Service
public class ShareLinkService {

  private final ShareLinkRepository links;
  private final FileMetadataRepository files;
  private final StoragePaths storagePaths;
  private final FileContents contents;
  private final FileService fileService;
  private final FileTreeService fileTree;
  private final AuthService authService;

  public ShareLinkService(
      ShareLinkRepository links,
      FileMetadataRepository files,
      StoragePaths storagePaths,
      FileContents contents,
      FileService fileService,
      FileTreeService fileTree,
      AuthService authService) {
    this.links = links;
    this.files = files;
    this.storagePaths = storagePaths;
    this.contents = contents;
    this.fileService = fileService;
    this.fileTree = fileTree;
    this.authService = authService;
  }

  /** A newly created link. The token is only ever available here, when the link is made. */
  public record CreatedLink(String token, Instant expiresAt) {}

  /**
   * Creates a link to the file or folder at {@code path} in the signed-in user's folder, which has
   * to be listed (have a row) and be in the store as what its row says it is.
   *
   * @throws NotFoundException if it isn't
   */
  @Transactional
  public CreatedLink create(String path, Duration lifetime) throws IOException {
    User user = authService.requireCurrentUser();
    StoragePath target = storagePaths.home(user).resolveItem(path);
    // Only what is listed, which has a row, and only while it is in the store, since a link to
    // anything else would only ever show that it is gone. Files copied in by hand are shared once
    // they are imported.
    FileMetadata file =
        files
            .findByPath(user.getId(), target.key())
            .orElseThrow(() -> new NotFoundException("Not found: " + target.key()));
    // As the link's pages would find it, or it would only ever be a 404.
    boolean stored =
        contents
            .of(target)
            .filter(entry -> entry.isDirectory() == Boolean.TRUE.equals(file.getIsDirectory()))
            .isPresent();
    if (!stored) {
      throw new NotFoundException("Not found: " + target.key());
    }

    String token = Tokens.newToken();
    Instant now = Instant.now();
    Instant expiresAt = now.plus(lifetime);
    links.save(new ShareLink(Tokens.hash(token), file, now, expiresAt, user));
    // Expired links can never open again; dropping them here keeps the table to about a week's.
    links.deleteExpiredBefore(now);
    return new CreatedLink(token, expiresAt);
  }

  /**
   * What a link's token opens, to download.
   *
   * @throws NotFoundException if the token is unknown, expired or revoked, or the item the link was
   *     made for is gone, has moved, or has changed between file and folder
   */
  public Download open(String token) throws IOException {
    return fileService.download(resolve(token).target());
  }

  /**
   * What a link opens, described for the page shown before downloading it: a folder with what it
   * holds.
   *
   * @throws NotFoundException as for {@link #open}
   */
  @Transactional(readOnly = true)
  public SharedItemDto describe(String token) throws IOException {
    Shared shared = resolve(token);
    FileMetadata file = shared.link().getFile();
    StoragePath target = shared.target();
    Instant expiresAt = shared.link().getExpiresAt();

    if (shared.isDirectory()) {
      List<SharedItemDto.Entry> contents = SharedItemDto.Entry.fromTree(fileTree.tree(target));
      long size = contents.stream().mapToLong(SharedItemDto.Entry::size).sum();
      return new SharedItemDto(
          target.name(), true, size, file.getUpdatedAt(), null, expiresAt, contents);
    }
    return new SharedItemDto(
        target.name(),
        false,
        shared.item().size(),
        file.getUpdatedAt(),
        PreviewType.of(target.name()).orElse(null),
        expiresAt,
        null);
  }

  /**
   * What a link opens, to show in the browser.
   *
   * @throws NotFoundException as for {@link #open}
   * @throws BadRequestException for a folder, or a file of a kind that cannot be previewed
   */
  public Preview preview(String token) throws IOException {
    return fileService.preview(resolve(token).target());
  }

  /** A link that still opens, and the item it opens. */
  private record Shared(ShareLink link, StoragePath target, Entry item) {

    boolean isDirectory() {
      return item.isDirectory();
    }
  }

  // A disabled account's links stop opening along with the account.
  private Shared resolve(String token) throws IOException {
    ShareLink link =
        links
            .findByTokenHash(Tokens.hash(token))
            .filter(l -> l.isActive(Instant.now()))
            .filter(l -> l.getFile().getOwner().isEnabled())
            .orElseThrow(ShareLinkService::linkNotFound);

    FileMetadata file = link.getFile();
    if (!file.getPath().equals(link.getPath())) {
      throw linkNotFound();
    }
    StoragePath target = storagePaths.home(file.getOwner()).resolveItem(link.getPath());
    boolean isDirectory = Boolean.TRUE.equals(file.getIsDirectory());
    Entry item = contents.of(target).orElseThrow(ShareLinkService::linkNotFound);
    if (item.isDirectory() != isDirectory) {
      throw linkNotFound();
    }
    return new Shared(link, target, item);
  }

  /**
   * The links to the item at {@code path} in the signed-in user's folder that still open, the
   * soonest to expire first.
   */
  @Transactional(readOnly = true)
  public List<ShareLinkDto> listActive(String path) {
    User user = authService.requireCurrentUser();
    StoragePath target = storagePaths.home(user).resolveItem(path);
    return files
        .findByPath(user.getId(), target.key())
        .map(file -> links.findActive(file, Instant.now()))
        .orElse(List.of())
        .stream()
        .map(ShareLinkDto::fromEntity)
        .toList();
  }

  /**
   * Revokes a link to one of the signed-in user's items so it no longer opens. Revoking it again
   * changes nothing.
   *
   * @throws NotFoundException if there is no such link, or it is to someone else's item
   */
  @Transactional
  public void revoke(Long id) {
    Long userId = authService.requireCurrentUser().getId();
    links
        .findById(id)
        .filter(link -> link.getFile().getOwner().getId().equals(userId))
        .orElseThrow(ShareLinkService::linkNotFound)
        .revoke(Instant.now());
  }

  private static NotFoundException linkNotFound() {
    return new NotFoundException("Share link not found");
  }
}
