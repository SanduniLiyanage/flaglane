package io.github.sanduniliyanage.flaglane.account.persistence;

import io.github.sanduniliyanage.flaglane.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A row of {@code users}. Never leaves the service layer; see {@code Account}. */
@Entity
@Table(name = "users")
public class UserEntity extends AssignedIdEntity {

  @Column(name = "email", nullable = false)
  private String email;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "display_name")
  private String displayName;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected UserEntity() {}

  public UserEntity(
      UUID id, String email, String passwordHash, String displayName, Instant createdAt) {
    super(id);
    this.email = email;
    this.passwordHash = passwordHash;
    this.displayName = displayName;
    this.createdAt = createdAt;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getDisplayName() {
    return displayName;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
