package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.exception.ConflictException;
import com.javadropbox.javadropbox.exception.ForbiddenException;
import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.exception.TooManyRequestsException;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import org.apache.catalina.connector.ClientAbortException;
import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.apache.tomcat.util.http.fileupload.MultipartStream.MalformedStreamException;
import org.apache.tomcat.util.http.fileupload.impl.SizeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
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
  private static final String OPERATION_FAILED = "The file operation could not be completed.";

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(BadRequestException.class)
  public ResponseEntity<Map<String, String>> handleBadRequest(BadRequestException ex) {
    return message(HttpStatus.BAD_REQUEST, ex.getMessage());
  }

  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<Map<String, String>> handleNotFound(NotFoundException ex) {
    return message(HttpStatus.NOT_FOUND, ex.getMessage());
  }

  @ExceptionHandler(ForbiddenException.class)
  public ResponseEntity<Map<String, String>> handleForbidden(ForbiddenException ex) {
    return message(HttpStatus.FORBIDDEN, ex.getMessage());
  }

  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<Map<String, String>> handleConflict(ConflictException ex) {
    return message(HttpStatus.CONFLICT, ex.getMessage());
  }

  @ExceptionHandler(TooManyRequestsException.class)
  public ResponseEntity<Map<String, String>> handleTooManyRequests(TooManyRequestsException ex) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfter().toSeconds()))
        .body(Map.of("message", ex.getMessage()));
  }

  // Two requests creating the same path at once: the unique constraint on the path lets one win.
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Map<String, String>> handleIntegrity(DataIntegrityViolationException ex) {
    log.warn("Rejected a change that conflicts with the database", ex);
    return message(HttpStatus.CONFLICT, "That conflicts with another change. Please try again.");
  }

  // Two requests changing the same rows at once, where the database made one of them give way.
  @ExceptionHandler(ConcurrencyFailureException.class)
  public ResponseEntity<Map<String, String>> handleConcurrency(ConcurrencyFailureException ex) {
    log.warn("Rejected a change that raced another one", ex);
    return message(HttpStatus.CONFLICT, "That conflicts with another change. Please try again.");
  }

  // Disk failures carry absolute paths and other server details; log them, don't return them.
  @ExceptionHandler(IOException.class)
  public ResponseEntity<Map<String, String>> handleIo(IOException ex) {
    log.error("File operation failed", ex);
    return message(HttpStatus.INTERNAL_SERVER_ERROR, OPERATION_FAILED);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<Map<String, String>> handleMaxSize(MaxUploadSizeExceededException ex) {
    return message(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE);
  }

  @ExceptionHandler(MultipartException.class)
  public ResponseEntity<Map<String, String>> handleMultipart(MultipartException ex) {
    Throwable root = ex;
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      // Tomcat reports its own size limits as a SizeException somewhere in the cause chain.
      if (cause instanceof SizeException) {
        return message(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE);
      }
      root = cause;
    }
    // Tomcat wraps anything that goes wrong while reading the parts the same way; only the
    // innermost cause tells a malformed request from the server failing to store it, e.g. in an
    // unwritable or full temporary folder.
    if (root instanceof IOException && !isClientFault(root)) {
      log.error("Could not store an upload while reading the request", ex);
      return message(HttpStatus.INTERNAL_SERVER_ERROR, OPERATION_FAILED);
    }
    return message(HttpStatus.BAD_REQUEST, "Invalid multipart request.");
  }

  private static boolean isClientFault(Throwable cause) {
    return cause instanceof MalformedStreamException
        || cause instanceof FileUploadException
        || cause instanceof ClientAbortException
        || cause instanceof EOFException
        || cause instanceof SocketTimeoutException;
  }

  static ResponseEntity<Map<String, String>> message(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("message", message));
  }
}
