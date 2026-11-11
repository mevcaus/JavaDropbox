package com.javadropbox.javadropbox.dto;

import org.jspecify.annotations.Nullable;

/**
 * An account as admins see it.
 *
 * @param role {@code ADMIN} or {@code USER}
 * @param quotaBytes the most it may store, previous versions included; null for no limit
 * @param usedBytes what it stores now, previous versions included
 */
public record AccountDto(
    Long id,
    String username,
    String role,
    boolean enabled,
    @Nullable Long quotaBytes,
    long usedBytes) {}
