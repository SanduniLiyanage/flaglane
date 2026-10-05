package io.github.sanduniliyanage.flaglane.flag.persistence;

import io.github.sanduniliyanage.flaglane.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code flag_configs}: one flag in one environment (FR-FLG-006).
 *
 * <p>{@code off_value} is not mapped for writing. The column exists so that making it configurable
 * later is a value change rather than a migration; until then the database fixes it at {@code
 * false} and no code here can say otherwise (ADR-009).
 */
@Entity
@Table(name = "flag_configs")
public class FlagConfigEntity extends AssignedIdEntity {

  @Column(name = "flag_id", nullable = false, updatable = false)
  private UUID flagId;

  @Column(name = "environment_id", nullable = false, updatable = false)
  private UUID environmentId;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "off_value", nullable = false, insertable = false, updatable = false)
  private boolean offValue;

  @Column(name = "fallthrough_value", nullable = false)
  private boolean fallthroughValue;

  @Column(name = "rollout_basis_points", nullable = false)
  private int rolloutBasisPoints;

  @Column(name = "rollout_salt", nullable = false)
  private String rolloutSalt;

  /** Optimistic lock: two concurrent writes to one configuration cannot both win silently. */
  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected FlagConfigEntity() {}

  /**
   * A configuration as a new flag or a new environment gets one (FR-FLG-003, FR-ENV-004): disabled,
   * falling through to {@code false}, at 0%, salted with the flag key (ADR-010).
   */
  public static FlagConfigEntity newDefault(
      UUID flagId, String flagKey, UUID environmentId, Instant at) {
    FlagConfigEntity config = new FlagConfigEntity(UUID.randomUUID());
    config.flagId = flagId;
    config.environmentId = environmentId;
    config.enabled = false;
    config.fallthroughValue = false;
    config.rolloutBasisPoints = 0;
    config.rolloutSalt = flagKey;
    config.updatedAt = at;
    return config;
  }

  private FlagConfigEntity(UUID id) {
    super(id);
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public void setFallthroughValue(boolean fallthroughValue) {
    this.fallthroughValue = fallthroughValue;
  }

  public void setRolloutBasisPoints(int rolloutBasisPoints) {
    this.rolloutBasisPoints = rolloutBasisPoints;
  }

  public void setRolloutSalt(String rolloutSalt) {
    this.rolloutSalt = rolloutSalt;
  }

  public void touch(Instant at) {
    this.updatedAt = at;
  }

  public UUID getFlagId() {
    return flagId;
  }

  public UUID getEnvironmentId() {
    return environmentId;
  }

  public boolean isEnabled() {
    return enabled;
  }

  /** Always {@code false} in v0.x. */
  public boolean getOffValue() {
    return offValue;
  }

  public boolean getFallthroughValue() {
    return fallthroughValue;
  }

  public int getRolloutBasisPoints() {
    return rolloutBasisPoints;
  }

  public String getRolloutSalt() {
    return rolloutSalt;
  }

  public long getVersion() {
    return version;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
