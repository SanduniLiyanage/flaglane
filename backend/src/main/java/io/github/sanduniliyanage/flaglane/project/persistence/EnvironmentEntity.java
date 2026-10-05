package io.github.sanduniliyanage.flaglane.project.persistence;

import io.github.sanduniliyanage.flaglane.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A row of {@code environments}. */
@Entity
@Table(name = "environments")
public class EnvironmentEntity extends AssignedIdEntity {

  @Column(name = "project_id", nullable = false, updatable = false)
  private UUID projectId;

  @Column(name = "key", nullable = false, updatable = false)
  private String key;

  @Column(name = "name", nullable = false)
  private String name;

  /**
   * Bumped in the transaction of every write that changes what the environment serves; the source
   * of the SDK ETag and the stream's change events (docs/DATABASE.md).
   */
  @Column(name = "ruleset_version", nullable = false)
  private long rulesetVersion;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected EnvironmentEntity() {}

  public EnvironmentEntity(UUID id, UUID projectId, String key, String name, Instant createdAt) {
    super(id);
    this.projectId = projectId;
    this.key = key;
    this.name = name;
    this.rulesetVersion = 0;
    this.createdAt = createdAt;
  }

  public UUID getProjectId() {
    return projectId;
  }

  public String getKey() {
    return key;
  }

  public String getName() {
    return name;
  }

  public long getRulesetVersion() {
    return rulesetVersion;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
