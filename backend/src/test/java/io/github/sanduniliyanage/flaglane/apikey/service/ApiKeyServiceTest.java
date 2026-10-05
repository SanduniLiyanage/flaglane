package io.github.sanduniliyanage.flaglane.apikey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKeyEvents;
import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKeyFormat;
import io.github.sanduniliyanage.flaglane.apikey.domain.IssuedApiKey;
import io.github.sanduniliyanage.flaglane.apikey.domain.KeyType;
import io.github.sanduniliyanage.flaglane.apikey.persistence.ApiKeyEntity;
import io.github.sanduniliyanage.flaglane.apikey.persistence.ApiKeyRepository;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditAction;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditEvent;
import io.github.sanduniliyanage.flaglane.audit.service.AuditLog;
import io.github.sanduniliyanage.flaglane.common.errors.NotFoundException;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantScopes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class ApiKeyServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-05T09:30:00Z");

  private final ApiKeyRepository keys = mock(ApiKeyRepository.class);
  private final AuditLog audit = mock(AuditLog.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final ApiKeyService service =
      new ApiKeyService(keys, audit, events, Clock.fixed(NOW, ZoneOffset.UTC));

  private final ProjectScope project =
      TenantScopes.project(UUID.randomUUID(), "storefront", UUID.randomUUID());
  private final EnvironmentScope production =
      TenantScopes.environment(project, UUID.randomUUID(), "production");

  @Test
  void anIssuedKeyIsStoredAsItsHashAndPrefixNeverItself() {
    IssuedApiKey issued = service.issue(production, "checkout", KeyType.SERVER);

    ApiKeyEntity stored = savedKey();
    assertThat(issued.secret()).startsWith("flg_srv_");
    assertThat(stored.getKeyHash()).isEqualTo(ApiKeyFormat.hash(issued.secret()));
    assertThat(stored.getKeyPrefix()).isEqualTo(issued.secret().substring(0, 16));
    assertThat(stored.getEnvironmentId()).isEqualTo(production.environmentId());
    assertThat(issued.toString()).doesNotContain(issued.secret());
  }

  @Test
  void issuingIsAuditedWithoutTheKeyOrItsHash() {
    IssuedApiKey issued = service.issue(production, "checkout", KeyType.CLIENT);

    AuditEvent event = recorded();
    assertThat(event.action()).isEqualTo(AuditAction.KEY_CREATED);
    assertThat(event.environmentId()).isEqualTo(production.environmentId());
    assertThat(event.actorId()).isEqualTo(project.userId());
    assertThat(event.newValue())
        .containsEntry("type", "client")
        .containsEntry("prefix", issued.secret().substring(0, 16));
    assertThat(event.newValue().toString())
        .doesNotContain(issued.secret())
        .doesNotContain(ApiKeyFormat.hash(issued.secret()));
  }

  @Test
  void anIssuedKeyIsAnnouncedForTheCache() {
    IssuedApiKey issued = service.issue(production, "checkout", KeyType.SERVER);

    ArgumentCaptor<ApiKeyEvents.Issued> event = ArgumentCaptor.forClass(ApiKeyEvents.Issued.class);
    verify(events).publishEvent(event.capture());
    assertThat(event.getValue().keyHash()).isEqualTo(ApiKeyFormat.hash(issued.secret()));
    assertThat(event.getValue().credential().keyType()).isEqualTo(KeyType.SERVER);
    assertThat(event.getValue().credential().environmentId()).isEqualTo(production.environmentId());
  }

  @Test
  void revokingAKeyStampsItAuditsItAndAnnouncesIt() {
    ApiKeyEntity key = existingKey();

    service.revoke(production, key.id());

    assertThat(key.getRevokedAt()).isEqualTo(NOW);
    AuditEvent event = recorded();
    assertThat(event.action()).isEqualTo(AuditAction.KEY_REVOKED);
    assertThat(event.previousValue()).containsEntry("revokedAt", null);
    assertThat(event.newValue()).containsEntry("revokedAt", NOW.toString());
    verify(events).publishEvent(any(ApiKeyEvents.Revoked.class));
  }

  @Test
  void revokingARevokedKeyChangesAndRecordsNothing() {
    ApiKeyEntity key = existingKey();
    key.revoke(NOW.minusSeconds(60));

    service.revoke(production, key.id());

    assertThat(key.getRevokedAt()).isEqualTo(NOW.minusSeconds(60));
    verifyNoInteractions(audit, events);
  }

  @Test
  void aKeyFromAnotherEnvironmentIsNotFound() {
    UUID elsewhere = UUID.randomUUID();
    when(keys.find(production, elsewhere)).thenReturn(Optional.empty());

    assertThatExceptionOfType(NotFoundException.class)
        .isThrownBy(() -> service.revoke(production, elsewhere))
        .withMessage(ApiKeyService.NOT_FOUND);
  }

  private ApiKeyEntity existingKey() {
    ApiKeyEntity key =
        new ApiKeyEntity(
            UUID.randomUUID(),
            production.environmentId(),
            "hash",
            "flg_srv_abcdefgh",
            KeyType.SERVER,
            "k",
            NOW.minusSeconds(3600));
    when(keys.find(production, key.id())).thenReturn(Optional.of(key));
    return key;
  }

  private ApiKeyEntity savedKey() {
    ArgumentCaptor<ApiKeyEntity> saved = ArgumentCaptor.forClass(ApiKeyEntity.class);
    verify(keys).saveAndFlush(saved.capture());
    return saved.getValue();
  }

  private AuditEvent recorded() {
    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).record(event.capture());
    return event.getValue();
  }
}
