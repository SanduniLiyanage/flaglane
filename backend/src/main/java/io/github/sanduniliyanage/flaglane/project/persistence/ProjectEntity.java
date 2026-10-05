package io.github.sanduniliyanage.flaglane.project.persistence;

import io.github.sanduniliyanage.flaglane.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A row of {@code projects}. The key is immutable, which a database trigger enforces too. */
@Entity
@Table(name = "projects")
public class ProjectEntity extends AssignedIdEntity {

  @Column(name = "owner_id", nullable = false, updatable = false)
  private UUID ownerId;

  @Column(name = "key", nullable = false, updatable = false)
  private String key;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ProjectEntity() {}

  public ProjectEntity(UUID id, UUID ownerId, String key, String name, Instant createdAt) {
    super(id);
    this.ownerId = ownerId;
    this.key = key;
    this.name = name;
    this.createdAt = createdAt;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public String getKey() {
    return key;
  }

  public String getName() {
    return name;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
