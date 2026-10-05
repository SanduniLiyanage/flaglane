package io.github.sanduniliyanage.flaglane.flag.persistence;

import io.github.sanduniliyanage.flaglane.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code flags}. Never deleted: archived, and restorable (FR-FLG-005, FR-FLG-007). The key
 * is immutable, which a database trigger enforces too (FR-FLG-002).
 */
@Entity
@Table(name = "flags")
public class FlagEntity extends AssignedIdEntity {

  @Column(name = "project_id", nullable = false, updatable = false)
  private UUID projectId;

  @Column(name = "key", nullable = false, updatable = false)
  private String key;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "description")
  private String description;

  @Column(name = "client_side_visible", nullable = false)
  private boolean clientSideVisible;

  @Column(name = "archived_at")
  private Instant archivedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected FlagEntity() {}

  public FlagEntity(
      UUID id,
      UUID projectId,
      String key,
      String name,
      String description,
      boolean clientSideVisible,
      Instant createdAt) {
    super(id);
    this.projectId = projectId;
    this.key = key;
    this.name = name;
    this.description = description;
    this.clientSideVisible = clientSideVisible;
    this.createdAt = createdAt;
  }

  public void rename(String name) {
    this.name = name;
  }

  public void describe(String description) {
    this.description = description;
  }

  public void setClientSideVisible(boolean clientSideVisible) {
    this.clientSideVisible = clientSideVisible;
  }

  public void archive(Instant at) {
    this.archivedAt = at;
  }

  public void restore() {
    this.archivedAt = null;
  }

  public boolean isArchived() {
    return archivedAt != null;
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

  public String getDescription() {
    return description;
  }

  public boolean isClientSideVisible() {
    return clientSideVisible;
  }

  public Instant getArchivedAt() {
    return archivedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
