package com.javadropbox.javadropbox.dto;

/**
 * Who is signed in, as /api/me and a successful sign-in answer it.
 *
 * @param role {@code ADMIN} or {@code USER}
 */
public record CurrentUser(String username, String role) {}
