package com.javadropbox.javadropbox.dto;

import java.time.Instant;

/**
 * What an invitation or password reset link is for, for the page that uses it.
 *
 * @param username the account the link creates, or whose password it sets
 */
public record AccountLinkInfo(String username, Instant expiresAt) {}
