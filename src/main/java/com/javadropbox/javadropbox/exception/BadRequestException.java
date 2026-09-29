package com.javadropbox.javadropbox.exception;

/** The request itself is invalid, e.g. a path outside the storage root. Maps to 400. */
public class BadRequestException extends RuntimeException {

  public BadRequestException(String message) {
    super(message);
  }
}
