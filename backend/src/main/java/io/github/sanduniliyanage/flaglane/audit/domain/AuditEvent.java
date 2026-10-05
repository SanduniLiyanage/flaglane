package io.github.sanduniliyanage.flaglane.audit.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One change, as the audit trail records it (FR-AUD-001): who, what, in which project, and the
 * state before and after. Environment and flag are present where the change has them and null where
 * it does not; previous state is null for a creation, new state null for a deletion.
 */
public record AuditEvent(
    UUID projectId,
    UUID environmentId,
    UUID flagId,
    UUID actorId,
    AuditAction action,
    Map<String, Object> previousValue,
    Map<String, Object> newValue) {

  public AuditEvent {
    Objects.requireNonNull(projectId, "projectId");
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(action, "action");
    previousValue =
        previousValue == null
            ? null
            : Collections.unmodifiableMap(new LinkedHashMap<>(previousValue));
    newValue = newValue == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(newValue));
  }

  /**
   * A copy that cannot change after the event is made: a recorded state that the caller could still
   * edit is not a record. Not {@code Map.copyOf}, which refuses the null values a state may hold,
   * such as an absent description.
   */
  public static Map<String, Object> snapshot(Map<String, Object> state) {
    return state == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(state));
  }

  /** A project-level event with no environment, no flag and no payload yet. */
  public static AuditEvent of(AuditAction action, UUID projectId, UUID actorId) {
    return new AuditEvent(projectId, null, null, actorId, action, null, null);
  }

  public AuditEvent environment(UUID environmentId) {
    return new AuditEvent(
        projectId, environmentId, flagId, actorId, action, previousValue, newValue);
  }

  public AuditEvent flag(UUID flagId) {
    return new AuditEvent(
        projectId, environmentId, flagId, actorId, action, previousValue, newValue);
  }

  public AuditEvent previous(Map<String, Object> previousValue) {
    return new AuditEvent(
        projectId, environmentId, flagId, actorId, action, previousValue, newValue);
  }

  public AuditEvent next(Map<String, Object> newValue) {
    return new AuditEvent(
        projectId, environmentId, flagId, actorId, action, previousValue, newValue);
  }
}
