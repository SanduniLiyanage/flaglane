package io.github.sanduniliyanage.flaglane.account.service;

import java.time.Instant;

/**
 * A signed access token and when it stops being accepted.
 *
 * @param value the compact JWT; a credential, so never logged
 */
public record IssuedToken(String value, Instant expiresAt) {

  @Override
  public String toString() {
    return "IssuedToken[value=<redacted>, expiresAt=" + expiresAt + "]";
  }
}
