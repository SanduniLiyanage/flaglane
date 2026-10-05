package io.github.sanduniliyanage.flaglane.audit.persistence;

import io.github.sanduniliyanage.flaglane.audit.domain.AuditEvent;
import io.github.sanduniliyanage.flaglane.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A row of {@code audit_entries}. Immutable to Hibernate as well as to the database: it is inserted
 * and never updated, so no dirty check can ever turn into an {@code UPDATE} the append-only trigger
 * would refuse (FR-AUD-002).
 */
@Entity
@Immutable
@Table(name = "audit_entries")
public class AuditEntryEntity extends AssignedIdEntity {

  @Column(name = "project_id", nullable = false, updatable = false)
  private UUID projectId;

  @Column(name = "environment_id", updatable = false)
  private UUID environmentId;

  @Column(name = "flag_id", updatable = false)
  private UUID flagId;

  @Column(name = "actor_id", nullable = false, updatable = false)
  private UUID actorId;

  @Column(name = "action", nullable = false, updatable = false)
  private String action;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "previous_value", updatable = false)
  private Map<String, Object> previousValue;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "new_value", updatable = false)
  private Map<String, Object> newValue;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AuditEntryEntity() {}

  public AuditEntryEntity(
      UUID id,
      UUID projectId,
      UUID environmentId,
      UUID flagId,
      UUID actorId,
      String action,
      Map<String, Object> previousValue,
      Map<String, Object> newValue,
      Instant createdAt) {
    super(id);
    this.projectId = projectId;
    this.environmentId = environmentId;
    this.flagId = flagId;
    this.actorId = actorId;
    this.action = action;
    this.previousValue = AuditEvent.snapshot(previousValue);
    this.newValue = AuditEvent.snapshot(newValue);
    this.createdAt = createdAt;
  }

  public UUID getProjectId() {
    return projectId;
  }

  public UUID getEnvironmentId() {
    return environmentId;
  }

  public UUID getFlagId() {
    return flagId;
  }

  public UUID getActorId() {
    return actorId;
  }

  public String getAction() {
    return action;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
