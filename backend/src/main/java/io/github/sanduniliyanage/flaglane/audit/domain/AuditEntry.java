package io.github.sanduniliyanage.flaglane.audit.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry of the audit trail as a reader sees it: what was recorded, with the actor, environment
 * and flag named rather than given by id.
 *
 * @param environmentKey the environment's key, or null for an entry with no environment. An
 *     environment deleted since keeps the key it had, from its deletion entry
 * @param environmentDeleted whether the entry names an environment that has since been deleted
 * @param flagKey the flag's key, or null for an entry with no flag. Flags are archived, never
 *     deleted, so a flag's key is always known
 * @param previousValue the state before, as recorded; null for a creation
 * @param newValue the state after, as recorded; null for a deletion
 */
public record AuditEntry(
    UUID id,
    Instant createdAt,
    String action,
    String actorEmail,
    String actorDisplayName,
    String environmentKey,
    boolean environmentDeleted,
    String flagKey,
    Map<String, Object> previousValue,
    Map<String, Object> newValue) {

  public AuditEntry {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(action, "action");
    previousValue =
        previousValue == null
            ? null
            : Collections.unmodifiableMap(new LinkedHashMap<>(previousValue));
    newValue = newValue == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(newValue));
  }

  /** Where the page after one ending with this entry starts. */
  public AuditCursor cursor() {
    return new AuditCursor(createdAt, id);
  }
}
