package com.javadropbox.javadropbox.exception;

/** The caller is not allowed to do this, e.g. a wrong setup code. Maps to 403. */
public class ForbiddenException extends RuntimeException {

  public ForbiddenException(String message) {
    super(message);
  }
}
