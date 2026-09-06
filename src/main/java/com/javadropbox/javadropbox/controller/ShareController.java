package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.DownloadableResource;
import com.javadropbox.javadropbox.service.FileServingService;
import com.javadropbox.javadropbox.service.ShareTokenService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Time-limited share links. {@code POST /api/share} requires the normal session
 * auth (covered by SecurityConfig's default rule). {@code GET /share/{token}} is
 * public &mdash; it is explicitly permitted in SecurityConfig because the whole
 * point is that someone without an account can use the link.
 */
@RestController
public class ShareController {

    private static final long DEFAULT_EXPIRATION_MINUTES = 24 * 60;
    private static final long MAX_EXPIRATION_MINUTES = 7 * 24 * 60;

    private final ShareTokenService shareTokenService;
    private final FileServingService fileServingService;

    public ShareController(ShareTokenService shareTokenService, FileServingService fileServingService) {
        this.shareTokenService = shareTokenService;
        this.fileServingService = fileServingService;
    }

    @PostMapping("/api/share")
    public ResponseEntity<?> createShareLink(
            @RequestParam String path,
            @RequestParam(defaultValue = "" + DEFAULT_EXPIRATION_MINUTES) long expirationMinutes,
            HttpServletRequest request) {

        if (expirationMinutes <= 0 || expirationMinutes > MAX_EXPIRATION_MINUTES) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "expirationMinutes must be between 1 and " + MAX_EXPIRATION_MINUTES));
        }

        if (!fileServingService.pathExists(path)) {
            return ResponseEntity.notFound().build();
        }

        String token = shareTokenService.generateToken(path, expirationMinutes);
        String shareUrl = ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath("/share/" + token)
                .replaceQuery(null)
                .build()
                .toUriString();

        return ResponseEntity.ok(Map.of(
                "url", shareUrl,
                "expiresAt", Instant.now().plus(Duration.ofMinutes(expirationMinutes)).toString()));
    }

    @GetMapping("/share/{token}")
    public ResponseEntity<Resource> downloadSharedFile(@PathVariable String token) {
        String path;
        try {
            path = shareTokenService.resolvePath(token);
        } catch (JwtException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        try {
            DownloadableResource downloadable = fileServingService.getResourceForPath(path);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(downloadable.contentType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadable.filename() + "\"")
                    .body(downloadable.resource());
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
