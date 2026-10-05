package io.github.sanduniliyanage.flaglane.apikey.service;

import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKey;
import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKeyEvents;
import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKeyFormat;
import io.github.sanduniliyanage.flaglane.apikey.domain.IssuedApiKey;
import io.github.sanduniliyanage.flaglane.apikey.domain.KeyType;
import io.github.sanduniliyanage.flaglane.apikey.domain.SdkCredential;
import io.github.sanduniliyanage.flaglane.apikey.persistence.ApiKeyEntity;
import io.github.sanduniliyanage.flaglane.apikey.persistence.ApiKeyRepository;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditAction;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditEvent;
import io.github.sanduniliyanage.flaglane.audit.service.AuditLog;
import io.github.sanduniliyanage.flaglane.common.errors.NotFoundException;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issuing, listing and revoking an environment's API keys (FR-KEY-001 to FR-KEY-003). */
@Service
public class ApiKeyService {

  static final String NOT_FOUND = "API key not found";

  private final ApiKeyRepository keys;
  private final AuditLog audit;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public ApiKeyService(
      ApiKeyRepository keys, AuditLog audit, ApplicationEventPublisher events, Clock clock) {
    this.keys = keys;
    this.audit = audit;
    this.events = events;
    this.clock = clock;
  }

  /** Metadata only: no key and no hash ever leaves this service after issue. */
  @Transactional(readOnly = true)
  public List<ApiKey> list(EnvironmentScope environment) {
    return keys.findAll(environment).stream().map(ApiKeyService::toApiKey).toList();
  }

  /**
   * Generates a key, stores its hash and prefix, and returns the key itself this once (FR-KEY-002).
   * The audit entry records the key's identity and prefix, never the key or its hash.
   */
  @Transactional
  public IssuedApiKey issue(EnvironmentScope environment, String name, KeyType type) {
    String secret = ApiKeyFormat.generate(type, random);
    ApiKeyEntity key =
        new ApiKeyEntity(
            UUID.randomUUID(),
            environment.environmentId(),
            ApiKeyFormat.hash(secret),
            ApiKeyFormat.prefix(secret),
            type,
            name,
            now());
    keys.saveAndFlush(key);
    audit.record(
        AuditEvent.of(
                AuditAction.KEY_CREATED, environment.projectId(), environment.project().userId())
            .environment(environment.environmentId())
            .next(describe(key)));
    events.publishEvent(new ApiKeyEvents.Issued(key.getKeyHash(), credential(key)));
    return new IssuedApiKey(toApiKey(key), secret);
  }

  /**
   * Revokes a key. From the moment this returns, the key is refused on every request (FR-KEY-003).
   * Revoking a revoked key changes nothing and records nothing.
   *
   * @throws NotFoundException if the environment has no such key
   */
  @Transactional
  public void revoke(EnvironmentScope environment, UUID keyId) {
    ApiKeyEntity key =
        keys.find(environment, keyId).orElseThrow(() -> new NotFoundException(NOT_FOUND));
    if (key.isRevoked()) {
      return;
    }
    Map<String, Object> before = describe(key);
    key.revoke(now());
    audit.record(
        AuditEvent.of(
                AuditAction.KEY_REVOKED, environment.projectId(), environment.project().userId())
            .environment(environment.environmentId())
            .previous(before)
            .next(describe(key)));
    events.publishEvent(new ApiKeyEvents.Revoked(key.getKeyHash(), credential(key)));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static SdkCredential credential(ApiKeyEntity key) {
    return new SdkCredential(key.id(), key.getEnvironmentId(), key.getKeyType());
  }

  private static Map<String, Object> describe(ApiKeyEntity key) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("id", key.id().toString());
    state.put("name", key.getName());
    state.put("type", key.getKeyType().value());
    state.put("prefix", key.getKeyPrefix());
    state.put("revokedAt", key.getRevokedAt() == null ? null : key.getRevokedAt().toString());
    return state;
  }

  private static ApiKey toApiKey(ApiKeyEntity key) {
    return new ApiKey(
        key.id(),
        key.getName(),
        key.getKeyType(),
        key.getKeyPrefix(),
        key.getCreatedAt(),
        key.getLastUsedAt(),
        key.getRevokedAt());
  }
}
