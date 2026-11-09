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

  /** A key that no longer does. Its open streams are closed too (FR-STR-003). */
  public record Revoked(String keyHash, SdkCredential credential) {}

  /**
   * A key this instance's cache held and, reloaded from the database, no longer holds: revoked
   * through another instance (ADR-041). Published after the cache has dropped it, outside any
   * transaction, so what reacts to a revoke here reacts to this as well.
   */
  public record NoLongerLive(SdkCredential credential) {}
}
