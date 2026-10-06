package com.javadropbox.javadropbox.exception;

/** Storing something would take the server over its storage limit. Maps to 507. */
public class InsufficientStorageException extends RuntimeException {

  public InsufficientStorageException(String message) {
    super(message);
  }
}
