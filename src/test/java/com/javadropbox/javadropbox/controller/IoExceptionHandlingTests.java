package com.javadropbox.javadropbox.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("File operation errors")
class IoExceptionHandlingTests {

  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @Test
  @DisplayName("a disk failure before the response starts is a 500 with a generic message")
  void diskFailureIsAServerError() {
    var response = handler.handleIo(new IOException("/srv/data: disk full"), response(false));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody()).doesNotContainValue("/srv/data: disk full");
  }

  @Test
  @DisplayName("a disk failure halfway through a download adds nothing to the response")
  void diskFailureAfterCommitWritesNothing() {
    assertThat(handler.handleIo(new IOException("read failed"), response(true))).isNull();
  }

  private static MockHttpServletResponse response(boolean committed) {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setCommitted(committed);
    return response;
  }
}
