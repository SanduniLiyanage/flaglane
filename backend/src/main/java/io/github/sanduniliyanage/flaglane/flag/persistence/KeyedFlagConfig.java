package io.github.sanduniliyanage.flaglane.flag.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * A configuration with the key of the flag it configures, as one row of a listing. A projection of
 * values rather than the entity, so that nothing read for a listing can be modified and flushed.
 */
public record KeyedFlagConfig(
    String flagKey,
    UUID environmentId,
    boolean enabled,
    boolean offValue,
    boolean fallthroughValue,
    int rolloutBasisPoints,
    String rolloutSalt,
    Instant updatedAt) {}
