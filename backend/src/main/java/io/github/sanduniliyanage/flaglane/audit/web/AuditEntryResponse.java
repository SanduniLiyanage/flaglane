package io.github.sanduniliyanage.flaglane.audit.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One audit entry: who did what, where, and the state before and after. */
public record AuditEntryResponse(
    UUID id,
    Instant createdAt,
    @Schema(example = "config.updated", description = "From the fixed vocabulary in DATABASE.md")
        String action,
    Actor actor,
    @Schema(
            example = "production",
            nullable = true,
            description =
                "The environment's key; null when the change was not to one environment."
                    + " A deleted environment keeps the key it had")
        String environment,
    @Schema(description = "Whether the environment has been deleted since")
        boolean environmentDeleted,
    @Schema(
            example = "new-checkout",
            nullable = true,
            description = "The flag's key; null when the change was not to one flag")
        String flag,
    @Schema(
            nullable = true,
            description =
                "The state before, as recorded; null for a creation. A rollout is recorded as"
                    + " stored, in basis points under rolloutBasisPoints (ADR-030)")
        Map<String, Object> previousValue,
    @Schema(nullable = true, description = "The state after, as recorded; null for a deletion")
        Map<String, Object> newValue) {

  public AuditEntryResponse {
    previousValue =
        previousValue == null
            ? null
            : Collections.unmodifiableMap(new LinkedHashMap<>(previousValue));
    newValue = newValue == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(newValue));
  }

  /** Who made the change. */
  public record Actor(
      @Schema(example = "amara@example.com") String email,
      @Schema(example = "Amara", nullable = true) String displayName) {}
}
