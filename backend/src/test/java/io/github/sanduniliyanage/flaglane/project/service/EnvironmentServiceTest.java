package io.github.sanduniliyanage.flaglane.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sanduniliyanage.flaglane.audit.domain.AuditAction;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditEvent;
import io.github.sanduniliyanage.flaglane.audit.service.AuditLog;
import io.github.sanduniliyanage.flaglane.common.errors.ConflictException;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantScopes;
import io.github.sanduniliyanage.flaglane.project.domain.EnvironmentCreated;
import io.github.sanduniliyanage.flaglane.project.persistence.EnvironmentEntity;
import io.github.sanduniliyanage.flaglane.project.persistence.EnvironmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

class EnvironmentServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-05T09:30:00Z");

  private final EnvironmentRepository environments = mock(EnvironmentRepository.class);
  private final TenantResolver tenants = mock(TenantResolver.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final AuditLog audit = mock(AuditLog.class);
  private final EnvironmentService service =
      new EnvironmentService(
          environments, tenants, events, audit, Clock.fixed(NOW, ZoneOffset.UTC));

  private final ProjectScope project =
      TenantScopes.project(UUID.randomUUID(), "storefront", UUID.randomUUID());
  private final EnvironmentScope qa = TenantScopes.environment(project, UUID.randomUUID(), "qa");

  @Test
  void creatingAnEnvironmentAnnouncesItInsideTheTransaction() {
    when(tenants.environment(project, "qa")).thenReturn(qa);

    service.create(project, "qa", "QA");

    verify(events).publishEvent(new EnvironmentCreated(qa));
  }

  @Test
  void creatingAnEnvironmentIsAudited() {
    when(tenants.environment(project, "qa")).thenReturn(qa);

    service.create(project, "qa", "QA");

    AuditEvent event = recorded();
    assertThat(event.action()).isEqualTo(AuditAction.ENVIRONMENT_CREATED);
    assertThat(event.projectId()).isEqualTo(project.projectId());
    assertThat(event.actorId()).isEqualTo(project.userId());
    assertThat(event.environmentId()).isNotNull();
    assertThat(event.newValue()).containsEntry("key", "qa").containsEntry("name", "QA");
  }

  @Test
  void aKeyAlreadyUsedInTheProjectIsAConflict() {
    when(environments.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq"));

    assertThatExceptionOfType(ConflictException.class)
        .isThrownBy(() -> service.create(project, "qa", "QA"))
        .withMessage(EnvironmentService.KEY_TAKEN);
    verifyNoInteractions(events, audit);
  }

  @Test
  void anEnvironmentHoldingAnActiveKeyIsNotDeleted() {
    when(environments.countActiveKeys(qa)).thenReturn(1L);

    assertThatExceptionOfType(ConflictException.class)
        .isThrownBy(() -> service.delete(qa))
        .withMessage(EnvironmentService.HAS_ACTIVE_KEYS);
    verify(environments, never()).delete(any());
    verifyNoInteractions(audit);
  }

  @Test
  void deletingAnEnvironmentAuditsItsLastStateThenDeletesIt() {
    when(environments.find(qa))
        .thenReturn(
            Optional.of(
                new EnvironmentEntity(qa.environmentId(), project.projectId(), "qa", "QA", NOW)));

    service.delete(qa);

    InOrder order = inOrder(audit, environments);
    order.verify(audit).record(any());
    order.verify(environments).delete(qa);
    AuditEvent event = recorded();
    assertThat(event.action()).isEqualTo(AuditAction.ENVIRONMENT_DELETED);
    assertThat(event.environmentId()).isEqualTo(qa.environmentId());
    assertThat(event.previousValue()).containsEntry("key", "qa");
    assertThat(event.newValue()).isNull();
  }

  private AuditEvent recorded() {
    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).record(event.capture());
    return event.getValue();
  }
}
