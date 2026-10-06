package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.Download;
import com.javadropbox.javadropbox.dto.ShareLinkDto;
import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.service.ShareLinkService;
import com.javadropbox.javadropbox.service.ShareLinkService.CreatedLink;
import com.javadropbox.javadropbox.service.UsageMetrics;
import com.javadropbox.javadropbox.service.UsageMetrics.Route;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Time-limited share links. Creating, listing and revoking them under {@code /api/share} requires
 * the normal session auth (covered by SecurityConfig's default rule). {@code GET /share/{token}} is
 * public &mdash; it is explicitly permitted in SecurityConfig because the whole point is that
 * someone without an account can use the link.
 */
@RestController
@Tag(name = "Share", description = "Endpoints for creating and accessing time-limited share links")
public class ShareController {

  private static final long DEFAULT_EXPIRATION_MINUTES = 24 * 60;

  private final ShareLinkService shareLinkService;
  private final UsageMetrics metrics;
  private final long maxExpirationMinutes;

  public ShareController(
      ShareLinkService shareLinkService,
      UsageMetrics metrics,
      @Value("${javadropbox.share.max-expiration:7d}") Duration maxExpiration) {
    this.shareLinkService = shareLinkService;
    this.metrics = metrics;
    this.maxExpirationMinutes = maxExpiration.toMinutes();
    if (maxExpirationMinutes < 1) {
      throw new IllegalStateException(
          "javadropbox.share.max-expiration must be at least a minute, not " + maxExpiration);
    }
  }

  @PostMapping("/api/share")
  @Operation(
      summary = "Create share link",
      description =
          "Creates a time-limited share link for a specific path. Requires authentication.")
  public Map<String, String> createShareLink(
      @RequestParam String path,
      @RequestParam(required = false) Long expirationMinutes,
      HttpServletRequest request)
      throws IOException {

    long minutes =
        expirationMinutes != null
            ? expirationMinutes
            : Math.min(DEFAULT_EXPIRATION_MINUTES, maxExpirationMinutes);
    if (minutes <= 0 || minutes > maxExpirationMinutes) {
      throw new BadRequestException(
          "expirationMinutes must be between 1 and " + maxExpirationMinutes);
    }

    CreatedLink link = shareLinkService.create(path, Duration.ofMinutes(minutes));
    metrics.shareLinkCreated();
    String shareUrl =
        ServletUriComponentsBuilder.fromRequestUri(request)
            .replacePath("/share/" + link.token())
            .replaceQuery(null)
            .build()
            .toUriString();

    return Map.of("url", shareUrl, "expiresAt", link.expiresAt().toString());
  }

  @GetMapping("/api/share")
  @Operation(
      summary = "List share links",
      description =
          "Lists the links to a path that have not expired or been revoked, the soonest to expire"
              + " first. Requires authentication.")
  public List<ShareLinkDto> listShareLinks(@RequestParam String path) {
    return shareLinkService.listActive(path);
  }

  @DeleteMapping("/api/share/{id}")
  @Operation(
      summary = "Revoke share link",
      description = "Revokes a share link so it stops working at once. Requires authentication.")
  public Map<String, String> revokeShareLink(@PathVariable Long id) {
    shareLinkService.revoke(id);
    return Map.of("message", "Share link revoked");
  }

  @GetMapping("/share/{token}")
  @Operation(
      summary = "Download shared file",
      description = "Downloads a file using a share token. Publicly accessible.")
  public ResponseEntity<Resource> downloadSharedFile(
      @PathVariable String token, HttpServletResponse response) throws IOException {
    // Whatever the reason a link does not open, the public gets a bare 404: the messages name
    // paths, which are none of their business.
    Download download;
    try {
      download = shareLinkService.open(token);
    } catch (NotFoundException | BadRequestException e) {
      return ResponseEntity.notFound().build();
    }
    metrics.fileServed(Route.SHARE_LINK);
    return DownloadResponses.send(download, response);
  }
}
