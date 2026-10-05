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
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantScopes;
import io.github.sanduniliyanage.flaglane.flag.domain.Flag;
import io.github.sanduniliyanage.flaglane.flag.domain.FlagChange;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagConfigEntity;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagConfigRepository;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagEntity;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagRepository;
import io.github.sanduniliyanage.flaglane.project.service.RulesetVersions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class FlagServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-05T09:30:00Z");

  private final FlagRepository flags = mock(FlagRepository.class);
  private final FlagConfigRepository configs = mock(FlagConfigRepository.class);
  private final TenantResolver tenants = mock(TenantResolver.class);
  private final RulesetVersions versions = mock(RulesetVersions.class);
  private final AuditLog audit = mock(AuditLog.class);
  private final FlagService service =
      new FlagService(flags, configs, tenants, versions, audit, Clock.fixed(NOW, ZoneOffset.UTC));

  private final ProjectScope project =
      TenantScopes.project(UUID.randomUUID(), "storefront", UUID.randomUUID());
  private final EnvironmentScope development =
      TenantScopes.environment(project, UUID.randomUUID(), "development");
  private final EnvironmentScope production =
      TenantScopes.environment(project, UUID.randomUUID(), "production");

  @BeforeEach
  void twoEnvironments() {
    when(tenants.environments(project)).thenReturn(List.of(development, production));
  }

  @Test
  void aNewFlagGetsADisabledConfigurationInEveryEnvironment() {
    service.create(project, "new-checkout", "New checkout", null, false);

    List<FlagConfigEntity> created = savedConfigs();
    assertThat(created)
        .extracting(FlagConfigEntity::getEnvironmentId)
        .containsExactly(development.environmentId(), production.environmentId());
    assertThat(created)
        .allSatisfy(
            config -> {
              assertThat(config.isEnabled()).isFalse();
              assertThat(config.getFallthroughValue()).isFalse();
              assertThat(config.getRolloutBasisPoints()).isZero();
              assertThat(config.getRolloutSalt()).isEqualTo("new-checkout");
            });
  }

  @Test
  void aNewFlagIsHiddenFromClientKeysUnlessAskedAndIsAudited() {
    Flag flag = service.create(project, "new-checkout", "New checkout", " ", false);

    assertThat(flag.clientSideVisible()).isFalse();
    assertThat(flag.description()).isNull();
    AuditEvent event = recorded();
    assertThat(event.action()).isEqualTo(AuditAction.FLAG_CREATED);
    assertThat(event.flagId()).isNotNull();
    assertThat(event.environmentId()).isNull();
    verify(versions).changed(project);
  }

  @Test
  void aKeyUsedByAnyFlagInTheProjectIsAConflict() {
    when(flags.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq"));

    assertThatExceptionOfType(ConflictException.class)
        .isThrownBy(() -> service.create(project, "taken", "Taken", null, false))
        .withMessage(FlagService.KEY_TAKEN);
    verifyNoInteractions(audit, versions);
  }

  @Test
  void renamingAFlagIsAuditedButChangesNoRuleset() {
    FlagEntity flag = existing("checkout", false);

    service.update(project, "checkout", new FlagChange("Checkout v2", null, null));

    assertThat(flag.getName()).isEqualTo("Checkout v2");
    assertThat(recorded().action()).isEqualTo(AuditAction.FLAG_UPDATED);
    verify(versions, never()).changed(any(ProjectScope.class));
  }

  @Test
  void changingVisibilityChangesEveryEnvironmentsRuleset() {
    existing("checkout", false);

    service.update(project, "checkout", new FlagChange(null, null, true));

    verify(versions).changed(project);
  }

  @Test
  void anEditThatChangesNothingRecordsNothing() {
    existing("checkout", false);

    service.update(project, "checkout", new FlagChange("checkout", null, false));

    verifyNoInteractions(audit, versions);
  }

  @Test
  void anArchivedFlagCannotBeEdited() {
    existing("checkout", false).archive(NOW);

    assertThatExceptionOfType(ConflictException.class)
        .isThrownBy(() -> service.update(project, "checkout", new FlagChange("x", null, null)))
        .withMessage(FlagService.ARCHIVED);
  }

  @Test
  void archivingStampsTheFlagAndChangesEveryRuleset() {
    FlagEntity flag = existing("checkout", false);

    Flag archived = service.archive(project, "checkout");

    assertThat(archived.archivedAt()).isEqualTo(NOW);
    assertThat(flag.isArchived()).isTrue();
    assertThat(recorded().action()).isEqualTo(AuditAction.FLAG_ARCHIVED);
    verify(versions).changed(project);
  }

  @Test
  void archivingAnArchivedFlagChangesNothing() {
    existing("checkout", false).archive(NOW.minusSeconds(60));

    service.archive(project, "checkout");

    verifyNoInteractions(audit, versions);
  }

  @Test
  void restoringFillsInEnvironmentsCreatedWhileTheFlagWasArchived() {
    FlagEntity flag = existing("checkout", false);
    flag.archive(NOW.minusSeconds(60));
    when(configs.findEnvironmentsConfigured(project, "checkout"))
        .thenReturn(List.of(development.environmentId()));

    Flag restored = service.restore(project, "checkout");

    assertThat(restored.archivedAt()).isNull();
    assertThat(savedConfigs())
        .extracting(FlagConfigEntity::getEnvironmentId)
        .containsExactly(production.environmentId());
    assertThat(recorded().action()).isEqualTo(AuditAction.FLAG_RESTORED);
    verify(versions).changed(project);
  }

  @Test
  void restoringALiveFlagChangesNothing() {
    existing("checkout", false);

    service.restore(project, "checkout");

    verifyNoInteractions(audit, versions, configs);
  }

  @Test
  void anUnknownFlagIsNotFound() {
    when(flags.find(project, "nope")).thenReturn(Optional.empty());

    assertThatExceptionOfType(NotFoundException.class)
        .isThrownBy(() -> service.archive(project, "nope"))
        .withMessage(FlagService.NOT_FOUND);
  }

  private FlagEntity existing(String key, boolean visible) {
    FlagEntity flag =
        new FlagEntity(UUID.randomUUID(), project.projectId(), key, key, null, visible, NOW);
    when(flags.find(project, key)).thenReturn(Optional.of(flag));
    return flag;
  }

  @SuppressWarnings("unchecked")
  private List<FlagConfigEntity> savedConfigs() {
    ArgumentCaptor<Iterable<FlagConfigEntity>> saved = ArgumentCaptor.forClass(Iterable.class);
    verify(configs).saveAll(saved.capture());
    return (List<FlagConfigEntity>) saved.getValue();
  }

  private AuditEvent recorded() {
    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).record(event.capture());
    return event.getValue();
  }
}
