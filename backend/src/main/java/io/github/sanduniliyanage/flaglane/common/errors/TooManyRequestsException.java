package io.github.sanduniliyanage.flaglane.common.errors;

/**
 * The caller has used its allowance for now. Answered with 429 and a {@code Retry-After} of {@code
 * retryAfterSeconds}.
 */
public class TooManyRequestsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final long retryAfterSeconds;

  public TooManyRequestsException(String message, long retryAfterSeconds) {
    super(message);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
