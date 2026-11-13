package com.javadropbox.javadropbox.exception;

/**
 * Something the request needs can't be reached for now, such as the search service, while the rest
 * of the app works. Maps to 503.
 */
public class ServiceUnavailableException extends RuntimeException {

  public ServiceUnavailableException(String message) {
    super(message);
  }
}
