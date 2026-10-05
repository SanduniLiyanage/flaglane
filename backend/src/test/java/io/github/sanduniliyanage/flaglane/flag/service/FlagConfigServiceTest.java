package io.github.sanduniliyanage.flaglane.flag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sanduniliyanage.flaglane.audit.domain.AuditAction;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditEvent;
import io.github.sanduniliyanage.flaglane.audit.service.AuditLog;
import io.github.sanduniliyanage.flaglane.common.errors.ConflictException;
import io.github.sanduniliyanage.flaglane.common.errors.NotFoundException;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantScopes;
import io.github.sanduniliyanage.flaglane.flag.domain.ConfigChange;
import io.github.sanduniliyanage.flaglane.flag.domain.FlagConfiguration;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagConfigEntity;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagConfigRepository;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagEntity;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagRepository;
import io.github.sanduniliyanage.flaglane.project.service.RulesetVersions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FlagConfigServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-05T09:30:00Z");

  private final FlagConfigRepository configs = mock(FlagConfigRepository.class);
  private final FlagRepository flags = mock(FlagRepository.class);
  private final RulesetVersions versions = mock(RulesetVersions.class);
  private final AuditLog audit = mock(AuditLog.class);
  private final FlagConfigService service =
      new FlagConfigService(configs, flags, versions, audit, Clock.fixed(NOW, ZoneOffset.UTC));

  private final ProjectScope project =
      TenantScopes.project(UUID.randomUUID(), "storefront", UUID.randomUUID());
  private final EnvironmentScope production =
      TenantScopes.environment(project, UUID.randomUUID(), "production");

  private FlagEntity flag;
  private FlagConfigEntity config;

  @BeforeEach
  void aFlagWithAConfiguration() {
    flag =
        new FlagEntity(
            UUID.randomUUID(), project.projectId(), "checkout", "Checkout", null, false, NOW);
    config =
        FlagConfigEntity.newDefault(
            flag.id(), "checkout", production.environmentId(), NOW.minusSeconds(3600));
    when(flags.find(project, "checkout")).thenReturn(Optional.of(flag));
    when(configs.find(production, "checkout")).thenReturn(Optional.of(config));
  }

  @Test
  void theKillSwitchFallthroughAndRolloutChangeTogether() {
    FlagConfiguration updated =
        service.update(production, "checkout", new ConfigChange(true, false, 2_500, null));

    assertThat(updated.enabled()).isTrue();
    assertThat(updated.rolloutBasisPoints()).isEqualTo(2_500);
    assertThat(updated.rolloutSalt()).isEqualTo("checkout");
    assertThat(updated.offValue()).isFalse();
    assertThat(updated.updatedAt()).isEqualTo(NOW);
  }

  @Test
  void aChangeIsAuditedWithItsEnvironmentAndFlagAndChangesThatEnvironmentsRuleset() {
    service.update(production, "checkout", new ConfigChange(true, null, null, null));

    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).record(event.capture());
    assertThat(event.getValue().action()).isEqualTo(AuditAction.CONFIG_UPDATED);
    assertThat(event.getValue().environmentId()).isEqualTo(production.environmentId());
    assertThat(event.getValue().flagId()).isEqualTo(flag.id());
    assertThat(event.getValue().previousValue()).containsEntry("enabled", false);
    assertThat(event.getValue().newValue()).containsEntry("enabled", true);
    verify(versions).changed(production);
  }

  @Test
  void aChangeToTheValuesAlreadySetRecordsNothing() {
    service.update(production, "checkout", new ConfigChange(false, false, 0, "checkout"));

    verifyNoInteractions(audit, versions);
    assertThat(config.getUpdatedAt()).isEqualTo(NOW.minusSeconds(3600));
  }

  @Test
  void anArchivedFlagsConfigurationCannotBeChanged() {
    flag.archive(NOW);

    assertThatExceptionOfType(ConflictException.class)
        .isThrownBy(
            () -> service.update(production, "checkout", new ConfigChange(true, null, null, null)));
    verify(versions, never()).changed(any(EnvironmentScope.class));
  }

  @Test
  void anUnknownFlagIsNotFound() {
    when(flags.find(project, "nope")).thenReturn(Optional.empty());
    when(configs.find(production, "nope")).thenReturn(Optional.empty());

    assertThatExceptionOfType(NotFoundException.class)
        .isThrownBy(() -> service.get(production, "nope"));
    assertThatExceptionOfType(NotFoundException.class)
        .isThrownBy(
            () -> service.update(production, "nope", new ConfigChange(true, null, null, null)));
  }
}
