package io.github.sanduniliyanage.flaglane.flag.domain;

import java.time.Instant;

/**
 * A flag as the service returns it (FR-FLG-001).
 *
 * @param description {@code null} when none was given
 * @param archivedAt {@code null} while the flag is live
 */
public record Flag(
    String key,
    String name,
    String description,
    boolean clientSideVisible,
    Instant archivedAt,
    Instant createdAt) {}
