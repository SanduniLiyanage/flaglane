package io.github.sanduniliyanage.flaglane.apikey.persistence;

import io.github.sanduniliyanage.flaglane.apikey.domain.KeyType;
import io.github.sanduniliyanage.flaglane.common.persistence.AssignedIdEntity;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A row of {@code api_keys}: a hash and a prefix, never the key (NFR-SEC-001). */
@Entity
@Table(name = "api_keys")
public class ApiKeyEntity extends AssignedIdEntity {

  @Column(name = "environment_id", nullable = false, updatable = false)
  private UUID environmentId;

  @Column(name = "key_hash", nullable = false, updatable = false)
  private String keyHash;

  @Column(name = "key_prefix", nullable = false, updatable = false)
  private String keyPrefix;

  @Convert(converter = KeyTypeColumn.class)
  @Column(name = "key_type", nullable = false, updatable = false)
  private KeyType keyType;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ApiKeyEntity() {}

  public ApiKeyEntity(
      UUID id,
      UUID environmentId,
      String keyHash,
      String keyPrefix,
      KeyType keyType,
      String name,
      Instant createdAt) {
    super(id);
    this.environmentId = environmentId;
    this.keyHash = keyHash;
    this.keyPrefix = keyPrefix;
    this.keyType = keyType;
    this.name = name;
    this.createdAt = createdAt;
  }

  public void revoke(Instant at) {
    this.revokedAt = at;
  }

  public boolean isRevoked() {
    return revokedAt != null;
  }

  public UUID getEnvironmentId() {
    return environmentId;
  }

  public String getKeyHash() {
    return keyHash;
  }

  public String getKeyPrefix() {
    return keyPrefix;
  }

  public KeyType getKeyType() {
    return keyType;
  }

  public String getName() {
    return name;
  }

  public Instant getLastUsedAt() {
    return lastUsedAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  /** {@code server} and {@code client}, as the database's check constraint spells them. */
  @Converter
  static class KeyTypeColumn implements AttributeConverter<KeyType, String> {

    @Override
    public String convertToDatabaseColumn(KeyType type) {
      return type == null ? null : type.value();
    }

    @Override
    public KeyType convertToEntityAttribute(String value) {
      return value == null
          ? null
          : KeyType.fromValue(value)
              .orElseThrow(() -> new IllegalStateException("Unknown key type " + value));
    }
  }
}
