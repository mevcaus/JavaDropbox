package com.javadropbox.javadropbox.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Converts the zone-less {@link LocalDateTime} values held by the persistence layer into absolute
 * {@link Instant}s for the API.
 *
 * <p>Entities record their timestamps with {@code LocalDateTime.now()}, so the wall-clock value in
 * the database is only meaningful when read back in the server's own zone. Resolving it here is
 * what lets the API emit a true instant, which every client can then render in the viewer's
 * timezone.
 */
public final class Timestamps {

  private Timestamps() {}

  public static Instant toInstant(LocalDateTime localDateTime) {
    if (localDateTime == null) {
      return null;
    }
    return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
  }
}
