package com.javadropbox.javadropbox.dto;

import java.time.Instant;

/**
 * An invitation that has not been used or expired yet. The link itself is only shown once, when the
 * invitation is made.
 *
 * @param role {@code ADMIN} or {@code USER}, for the account it creates
 * @param quotaBytes the account's quota, null for none
 */
public record InviteDto(
    Long id,
    String username,
    String role,
    Long quotaBytes,
    Instant createdAt,
    Instant expiresAt,
    String createdBy) {}
