package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.Download;
import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.service.ShareLinkService;
import com.javadropbox.javadropbox.service.ShareLinkService.CreatedLink;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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

  private final ShareLinkService shareLinkService;

  public ShareController(ShareLinkService shareLinkService) {
    this.shareLinkService = shareLinkService;
  }

  @PostMapping("/api/share")
  @Operation(
      summary = "Create share link",
      description =
          "Creates a time-limited share link for a specific path. Requires authentication.")
  public Map<String, String> createShareLink(
      @RequestParam String path,
      @RequestParam(defaultValue = "" + DEFAULT_EXPIRATION_MINUTES) long expirationMinutes,
      HttpServletRequest request)
      throws IOException {

    if (expirationMinutes <= 0 || expirationMinutes > MAX_EXPIRATION_MINUTES) {
      throw new BadRequestException(
          "expirationMinutes must be between 1 and " + MAX_EXPIRATION_MINUTES);
    }

    CreatedLink link = shareLinkService.create(path, Duration.ofMinutes(expirationMinutes));
    String shareUrl =
        ServletUriComponentsBuilder.fromRequestUri(request)
            .replacePath("/share/" + link.token())
            .replaceQuery(null)
            .build()
            .toUriString();

    return Map.of("url", shareUrl, "expiresAt", link.expiresAt().toString());
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
    return DownloadResponses.send(download, response);
  }
}
