package io.github.sanduniliyanage.flaglane.apikey.persistence;

import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * API keys. Dashboard reads go through an {@link EnvironmentScope} (NFR-SEC-004). Two methods do
 * not, and are not reachable from any dashboard request: the key cache loads every live key at
 * startup, and the last-used flush stamps keys it has seen authenticate.
 */
public interface ApiKeyRepository extends Repository<ApiKeyEntity, UUID> {

  ApiKeyEntity saveAndFlush(ApiKeyEntity key);

  @Query(
      "select k from ApiKeyEntity k where k.environmentId = :#{#environment.environmentId()}"
          + " order by k.createdAt, k.id")
  List<ApiKeyEntity> findAll(@Param("environment") EnvironmentScope environment);

  @Query(
      "select k from ApiKeyEntity k where k.id = :id"
          + " and k.environmentId = :#{#environment.environmentId()}")
  Optional<ApiKeyEntity> find(
      @Param("environment") EnvironmentScope environment, @Param("id") UUID id);

  /** Every key not revoked, across all tenants: the key cache's whole contents (FR-KEY-007). */
  @Query("select k from ApiKeyEntity k where k.revokedAt is null")
  List<ApiKeyEntity> findAllLive();

  /**
   * Stamps a key's last use. Only ever moves the stamp forward, so a late flush cannot overwrite a
   * newer one (FR-KEY-006).
   */
  @Modifying
  @Query(
      "update ApiKeyEntity k set k.lastUsedAt = :at where k.id = :id"
          + " and (k.lastUsedAt is null or k.lastUsedAt < :at)")
  int markUsed(@Param("id") UUID id, @Param("at") Instant at);
}
