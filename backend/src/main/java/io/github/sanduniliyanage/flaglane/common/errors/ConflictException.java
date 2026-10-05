package io.github.sanduniliyanage.flaglane.common.errors;

/**
 * The request is well formed but contradicts what already exists: a second account for one email
 * address, a key that is taken. Answered with 409. The message is shown to the caller, so it must
 * not carry anything the caller should not see.
 */
public class ConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ConflictException(String message) {
    super(message);
  }

  public ConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
