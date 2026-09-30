package com.javadropbox.javadropbox.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@DisplayName("API exception handler")
class ApiExceptionHandlerTests {

  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @Test
  @DisplayName("a change that lost a race with another one is a 409, not a 500")
  void concurrencyFailureIsAConflict() {
    var response =
        handler.handleConcurrency(new ObjectOptimisticLockingFailureException("FileMetadata", 1L));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).containsKey("message");
  }
}
