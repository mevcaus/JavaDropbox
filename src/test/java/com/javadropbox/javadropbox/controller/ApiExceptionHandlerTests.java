package com.javadropbox.javadropbox.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileNotFoundException;
import java.io.IOException;
import org.apache.catalina.connector.ClientAbortException;
import org.apache.tomcat.util.http.fileupload.MultipartStream.MalformedStreamException;
import org.apache.tomcat.util.http.fileupload.impl.IOFileUploadException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.multipart.MultipartException;

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

  @Test
  @DisplayName("an upload the server failed to store is a 500, without the disk details")
  void multipartServerFaultIsA500() {
    var response =
        handler.handleMultipart(
            multipartFailure(new FileNotFoundException("/tmp/upload_1.tmp (Permission denied)")));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().get("message")).doesNotContain("/tmp");
  }

  @Test
  @DisplayName("a malformed upload, or one the client abandoned, is a 400")
  void multipartClientFaultIsA400() {
    assertThat(
            handler
                .handleMultipart(
                    multipartFailure(new MalformedStreamException("Stream ended unexpectedly")))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            handler
                .handleMultipart(multipartFailure(new ClientAbortException("reset")))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  // How Spring and Tomcat wrap a failure while reading the parts of a request.
  private static MultipartException multipartFailure(IOException cause) {
    return new MultipartException(
        "Failed to parse multipart servlet request",
        new IOFileUploadException("Processing of multipart/form-data request failed.", cause));
  }
}
