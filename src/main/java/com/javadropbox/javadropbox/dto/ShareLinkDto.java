package com.javadropbox.javadropbox.dto;

import com.javadropbox.javadropbox.model.ShareLink;
import java.time.Instant;

/**
 * One active share link. The link's URL is not included: only a hash of its token is stored, so the
 * URL is shown once, when the link is created.
 */
public record ShareLinkDto(Long id, Instant createdAt, Instant expiresAt, String createdBy) {

  public static ShareLinkDto fromEntity(ShareLink entity) {
    return new ShareLinkDto(
        entity.getId(),
        entity.getCreatedAt(),
        entity.getExpiresAt(),
        entity.getCreatedBy() != null ? entity.getCreatedBy().getUsername() : "Unknown");
  }
}
