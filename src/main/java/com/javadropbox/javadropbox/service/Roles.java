package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.model.User;
import java.util.Locale;

/** Roles as the API names them, {@code ADMIN} and {@code USER}, and as they are stored. */
final class Roles {

  private static final String PREFIX = "ROLE_";

  private Roles() {}

  /** {@code ADMIN} for {@link User#ROLE_ADMIN}, {@code USER} for {@link User#ROLE_USER}. */
  static String name(String role) {
    return role.substring(PREFIX.length());
  }

  /**
   * The stored role for a name the API was given.
   *
   * @throws BadRequestException for anything but {@code ADMIN} or {@code USER}, in any case
   */
  static String parse(String name) {
    String role = PREFIX + (name == null ? "" : name.trim().toUpperCase(Locale.ROOT));
    if (!role.equals(User.ROLE_ADMIN) && !role.equals(User.ROLE_USER)) {
      throw new BadRequestException("role must be ADMIN or USER");
    }
    return role;
  }
}
