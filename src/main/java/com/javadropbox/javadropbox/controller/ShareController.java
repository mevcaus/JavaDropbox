package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.Download;
import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.service.FileService;
import com.javadropbox.javadropbox.service.ShareTokenService;
import io.jsonwebtoken.JwtException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Time-limited share links. {@code POST /api/share} requires the normal session auth (covered by
 * SecurityConfig's default rule). {@code GET /share/{token}} is public &mdash; it is explicitly
 * permitted in SecurityConfig because the whole point is that someone without an account can use
 * the link.
 */
@RestController
@Tag(name = "Share", description = "Endpoints for creating and accessing time-limited share links")
public class ShareController {

  private static final long DEFAULT_EXPIRATION_MINUTES = 24 * 60;
  private static final long MAX_EXPIRATION_MINUTES = 7 * 24 * 60;

  private final ShareTokenService shareTokenService;
  private final FileService fileService;

  public ShareController(ShareTokenService shareTokenService, FileService fileService) {
    this.shareTokenService = shareTokenService;
    this.fileService = fileService;
  }

  @PostMapping("/api/share")
  @Operation(
      summary = "Create share link",
      description =
          "Creates a time-limited share link for a specific path. Requires authentication.")
  public ResponseEntity<?> createShareLink(
      @RequestParam String path,
      @RequestParam(defaultValue = "" + DEFAULT_EXPIRATION_MINUTES) long expirationMinutes,
      HttpServletRequest request) {

    if (expirationMinutes <= 0 || expirationMinutes > MAX_EXPIRATION_MINUTES) {
      return ResponseEntity.badRequest()
          .body(
              Map.of(
                  "message", "expirationMinutes must be between 1 and " + MAX_EXPIRATION_MINUTES));
    }

    if (!fileService.exists(path)) {
      return ResponseEntity.notFound().build();
    }

    String token = shareTokenService.generateToken(path, expirationMinutes);
    String shareUrl =
        ServletUriComponentsBuilder.fromRequestUri(request)
            .replacePath("/share/" + token)
            .replaceQuery(null)
            .build()
            .toUriString();

    return ResponseEntity.ok(
        Map.of(
            "url",
            shareUrl,
            "expiresAt",
            Instant.now().plus(Duration.ofMinutes(expirationMinutes)).toString()));
  }

  @GetMapping("/share/{token}")
  @Operation(
      summary = "Download shared file",
      description = "Downloads a file using a share token. Publicly accessible.")
  public ResponseEntity<Resource> downloadSharedFile(
      @PathVariable String token, HttpServletResponse response) throws IOException {
    String path;
    try {
      path = shareTokenService.resolvePath(token);
    } catch (JwtException e) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    // The link may name something that has since been deleted, or (for a token minted before
    // the root was refused) the root itself; either way there is nothing to hand out.
    Download download;
    try {
      download = fileService.download(path);
    } catch (NotFoundException | BadRequestException e) {
      return ResponseEntity.notFound().build();
    }
    return DownloadResponses.send(download, response);
  }
}
