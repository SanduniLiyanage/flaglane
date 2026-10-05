package io.github.sanduniliyanage.flaglane.targeting.persistence;

import io.github.sanduniliyanage.flaglane.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * A row of {@code user_overrides}. Replaced as a set, never edited in place. The user key is a real
 * user identifier: it is served to server keys only, never to client keys (FR-KEY-008).
 */
@Entity
@Immutable
@Table(name = "user_overrides")
public class UserOverrideEntity extends AssignedIdEntity {

  @Column(name = "flag_config_id", nullable = false, updatable = false)
  private UUID flagConfigId;

  @Column(name = "user_key", nullable = false, updatable = false)
  private String userKey;

  @Column(name = "value", nullable = false, updatable = false)
  private boolean value;

  protected UserOverrideEntity() {}

  public UserOverrideEntity(UUID id, UUID flagConfigId, String userKey, boolean value) {
    super(id);
    this.flagConfigId = flagConfigId;
    this.userKey = userKey;
    this.value = value;
  }

  public UUID getFlagConfigId() {
    return flagConfigId;
  }

  public String getUserKey() {
    return userKey;
  }

  public boolean getValue() {
    return value;
  }
}
