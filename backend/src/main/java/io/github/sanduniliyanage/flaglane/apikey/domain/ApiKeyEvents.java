package io.github.sanduniliyanage.flaglane.apikey.domain;

/**
 * Published inside the transaction that issues or revokes a key. The key cache applies them after
 * the transaction commits, so a key from a rolled-back issue never authenticates and a revoked key
 * stops authenticating before the revoke request returns (FR-KEY-003, FR-KEY-007).
 */
public final class ApiKeyEvents {

  private ApiKeyEvents() {}

  /** A key that now authenticates. */
  public record Issued(String keyHash, SdkCredential credential) {}

  /** A key that no longer does. Open streams for it are closed too, once streaming exists. */
  public record Revoked(String keyHash, SdkCredential credential) {}
}
