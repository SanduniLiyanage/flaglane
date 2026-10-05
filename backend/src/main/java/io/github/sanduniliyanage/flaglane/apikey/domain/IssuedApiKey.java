package io.github.sanduniliyanage.flaglane.apikey.domain;

/**
 * A newly issued key: its metadata and, this once, the key itself (FR-KEY-002).
 *
 * @param secret the plaintext key; a credential, so never logged (NFR-SEC-003)
 */
public record IssuedApiKey(ApiKey key, String secret) {

  @Override
  public String toString() {
    return "IssuedApiKey[key=" + key + ", secret=<redacted>]";
  }
}
