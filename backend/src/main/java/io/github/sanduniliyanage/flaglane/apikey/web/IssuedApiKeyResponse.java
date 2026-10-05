package io.github.sanduniliyanage.flaglane.apikey.web;

import io.github.sanduniliyanage.flaglane.apikey.domain.IssuedApiKey;
import io.github.sanduniliyanage.flaglane.apikey.domain.KeyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * A newly issued key, the only response that ever carries one (FR-KEY-002). Flaglane keeps a hash;
 * a key lost after this is replaced, not recovered.
 */
public record IssuedApiKeyResponse(
    UUID id,
    String name,
    KeyType type,
    String prefix,
    Instant createdAt,
    @Schema(
            description = "The key. Shown once and never again",
            example = "flg_srv_Xk3mP9qaR2vT8wY1zB4cD6eF0gH5jK7lM9nQ2sU4xW6")
        String key) {

  static IssuedApiKeyResponse from(IssuedApiKey issued) {
    return new IssuedApiKeyResponse(
        issued.key().id(),
        issued.key().name(),
        issued.key().type(),
        issued.key().prefix(),
        issued.key().createdAt(),
        issued.secret());
  }

  @Override
  public String toString() {
    return "IssuedApiKeyResponse[id=" + id + ", prefix=" + prefix + ", key=<redacted>]";
  }
}
