package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DemoService reset times")
class DemoServiceTests {

  private final DemoService demo =
      new DemoService(
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          Clock.systemUTC(),
          "demo",
          "pw",
          LocalTime.of(10, 0),
          null);

  @Test
  @DisplayName("before the reset time, the next reset is the same day")
  void sameDay() {
    assertThat(demo.resetTimeAfter(Instant.parse("2026-10-06T09:59:59Z")))
        .isEqualTo(Instant.parse("2026-10-06T10:00:00Z"));
  }

  @Test
  @DisplayName("at or after the reset time, the next reset is the next day")
  void nextDay() {
    assertThat(demo.resetTimeAfter(Instant.parse("2026-10-06T10:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-07T10:00:00Z"));
    assertThat(demo.resetTimeAfter(Instant.parse("2026-10-06T23:30:00Z")))
        .isEqualTo(Instant.parse("2026-10-07T10:00:00Z"));
  }

  @Test
  @DisplayName("with no reset ever, one is due at once")
  void neverReset() {
    assertThat(demo.resetTimeAfter(Instant.MIN)).isEqualTo(Instant.MIN);
  }
}
