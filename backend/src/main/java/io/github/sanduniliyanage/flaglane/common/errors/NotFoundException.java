package io.github.sanduniliyanage.flaglane.common.errors;

/**
 * The resource does not exist for this caller, which is the same thing from where the caller
 * stands: another tenant's project is answered exactly like a project that does not exist, so
 * existence is not disclosed (FR-PRJ-003). Answered with 404.
 */
public class NotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public NotFoundException(String message) {
    super(message);
  }
}
