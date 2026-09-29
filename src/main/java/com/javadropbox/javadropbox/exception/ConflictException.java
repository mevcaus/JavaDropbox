package com.javadropbox.javadropbox.exception;

/** The request clashes with what is already there, e.g. creating a folder that exists. 409. */
public class ConflictException extends RuntimeException {

  public ConflictException(String message) {
    super(message);
  }
}
