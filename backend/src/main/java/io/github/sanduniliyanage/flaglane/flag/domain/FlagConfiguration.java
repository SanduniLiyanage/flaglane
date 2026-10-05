package io.github.sanduniliyanage.flaglane.flag.domain;

import java.time.Instant;

/**
 * One flag's configuration in one environment, as the service returns it (FR-FLG-006).
 *
 * @param offValue always {@code false} in v0.x (ADR-009)
 * @param rolloutBasisPoints 0 to 10000 (ADR-011); the management API shows whole percentages
 */
public record FlagConfiguration(
    String flagKey,
    String environmentKey,
    boolean enabled,
    boolean offValue,
    boolean fallthroughValue,
    int rolloutBasisPoints,
    String rolloutSalt,
    Instant updatedAt) {}
