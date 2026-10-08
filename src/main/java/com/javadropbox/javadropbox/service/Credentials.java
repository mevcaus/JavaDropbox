package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.exception.BadRequestException;
import java.nio.charset.StandardCharsets;

/** What usernames and passwords have to be, however an account gets one. */
public final class Credentials {

  public static final int MIN_PASSWORD_LENGTH = 8;

  // BCrypt only looks at the first 72 bytes, and Spring Security refuses anything longer.
  private static final int MAX_PASSWORD_BYTES = 72;
  private static final int MAX_USERNAME_LENGTH = 255;

  private Credentials() {}

  /**
   * The username to store: the one given, without surrounding spaces.
   *
   * @throws BadRequestException if it is empty or too long
   */
  public static String username(String username) {
    String name = username == null ? "" : username.trim();
    if (name.isEmpty()) {
      throw new BadRequestException("Username required");
    }
    if (name.length() > MAX_USERNAME_LENGTH) {
      throw new BadRequestException("Username is too long");
    }
    return name;
  }

  /**
   * Checks a new password.
   *
   * @throws BadRequestException if it is too short or too long
   */
  public static void checkPassword(String password) {
    if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
      throw new BadRequestException(
          "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
    }
    if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
      throw new BadRequestException("Password is too long");
    }
  }
}
