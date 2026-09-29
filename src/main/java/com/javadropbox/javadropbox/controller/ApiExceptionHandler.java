package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.exception.NotFoundException;
import java.util.Map;
import org.apache.tomcat.util.http.fileupload.impl.SizeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/** Turns exceptions into {@code {"message": ...}} responses with the right status. */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final String TOO_LARGE = "File too large! Maximum upload size exceeded.";

  @ExceptionHandler(BadRequestException.class)
  public ResponseEntity<Map<String, String>> handleBadRequest(BadRequestException ex) {
    return message(HttpStatus.BAD_REQUEST, ex.getMessage());
  }

  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<Map<String, String>> handleNotFound(NotFoundException ex) {
    return message(HttpStatus.NOT_FOUND, ex.getMessage());
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<Map<String, String>> handleMaxSize(MaxUploadSizeExceededException ex) {
    return message(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE);
  }

  @ExceptionHandler(MultipartException.class)
  public ResponseEntity<Map<String, String>> handleMultipart(MultipartException ex) {
    // Tomcat reports its own size limits as a SizeException somewhere in the cause chain.
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof SizeException) {
        return message(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE);
      }
    }
    return message(HttpStatus.BAD_REQUEST, "Invalid multipart request.");
  }

  static ResponseEntity<Map<String, String>> message(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("message", message));
  }
}
