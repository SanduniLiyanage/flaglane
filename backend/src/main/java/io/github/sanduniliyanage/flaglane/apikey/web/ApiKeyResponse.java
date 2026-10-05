package io.github.sanduniliyanage.flaglane.apikey.web;

import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKey;
import io.github.sanduniliyanage.flaglane.apikey.domain.KeyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** A key's metadata. Never the key, never its hash (FR-KEY-002). */
public record ApiKeyResponse(
    UUID id,
    @Schema(example = "checkout-service") String name,
    KeyType type,
    @Schema(example = "flg_srv_Xk3mP9qa", description = "Enough to tell keys apart; not secret")
        String prefix,
    Instant createdAt,
    @Schema(nullable = true, description = "Accurate to about a minute") Instant lastUsedAt,
    @Schema(nullable = true) Instant revokedAt) {

  static ApiKeyResponse from(ApiKey key) {
    return new ApiKeyResponse(
        key.id(),
        key.name(),
        key.type(),
        key.prefix(),
        key.createdAt(),
        key.lastUsedAt(),
        key.revokedAt());
  }
}
