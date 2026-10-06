package io.github.sanduniliyanage.flaglane.audit.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@link AuditTrailReader}'s query. The recorded states are the stored JSON, unparsed.
 */
public record AuditTrailRow(
    UUID id,
    Instant createdAt,
    String action,
    String actorEmail,
    String actorDisplayName,
    String environmentKey,
    boolean environmentDeleted,
    String flagKey,
    String previousValueJson,
    String newValueJson) {}
