package io.github.sanduniliyanage.flaglane.apikey.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A key's metadata: everything about it except the key itself, which exists only in the response
 * that issued it (FR-KEY-002).
 *
 * @param lastUsedAt {@code null} if never used; accurate to the minute (FR-KEY-006)
 * @param revokedAt {@code null} while the key is live
 */
public record ApiKey(
    UUID id,
    String name,
    KeyType type,
    String prefix,
    Instant createdAt,
    Instant lastUsedAt,
    Instant revokedAt) {}
