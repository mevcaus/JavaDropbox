package com.javadropbox.javadropbox.dto;

import java.time.Instant;

/**
 * A link just made: a share link, an invitation or a password reset. Its URL is only ever returned
 * here, since only a hash of its token is stored.
 */
public record NewLink(String url, Instant expiresAt) {}
