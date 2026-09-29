package com.javadropbox.javadropbox.exception;

/** The file, folder or version the request names does not exist. Maps to 404. */
public class NotFoundException extends RuntimeException {

  public NotFoundException(String message) {
    super(message);
  }
}
