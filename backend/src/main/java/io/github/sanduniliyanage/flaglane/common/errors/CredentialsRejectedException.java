package io.github.sanduniliyanage.flaglane.common.errors;

/**
 * Credentials were presented and are not valid. Answered with 401. One message for every cause —
 * unknown account or wrong password — so the response does not say which.
 */
public class CredentialsRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CredentialsRejectedException(String message) {
    super(message);
  }
}
