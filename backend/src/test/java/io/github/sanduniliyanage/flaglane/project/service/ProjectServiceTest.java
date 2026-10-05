package io.github.sanduniliyanage.flaglane.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.tuple;
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
import io.github.sanduniliyanage.flaglane.common.tenancy.OwnerScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantScopes;
import io.github.sanduniliyanage.flaglane.project.domain.Project;
import io.github.sanduniliyanage.flaglane.project.persistence.EnvironmentEntity;
import io.github.sanduniliyanage.flaglane.project.persistence.EnvironmentRepository;
import io.github.sanduniliyanage.flaglane.project.persistence.ProjectEntity;
import io.github.sanduniliyanage.flaglane.project.persistence.ProjectRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class ProjectServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-05T09:30:00.123456789Z");

  private final ProjectRepository projects = mock(ProjectRepository.class);
  private final EnvironmentRepository environments = mock(EnvironmentRepository.class);
  private final AuditLog audit = mock(AuditLog.class);
  private final OwnerScope owner = TenantScopes.owner(UUID.randomUUID());
  private final ProjectService service =
      new ProjectService(projects, environments, audit, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void aNewProjectBelongsToItsCreator() {
    Project project = service.create(owner, "storefront", "Storefront");

    ProjectEntity saved = savedProject();
    assertThat(saved.getOwnerId()).isEqualTo(owner.userId());
    assertThat(saved.getKey()).isEqualTo("storefront");
    assertThat(project.createdAt()).isEqualTo(Instant.parse("2026-10-05T09:30:00.123456Z"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void aNewProjectGetsDevelopmentStagingAndProductionEnvironments() {
    service.create(owner, "storefront", "Storefront");

    ArgumentCaptor<Iterable<EnvironmentEntity>> created = ArgumentCaptor.forClass(Iterable.class);
    verify(environments).saveAll(created.capture());
    assertThat(created.getValue())
        .extracting(EnvironmentEntity::getKey, EnvironmentEntity::getProjectId)
        .containsExactly(
            tuple("development", savedProject().id()),
            tuple("staging", savedProject().id()),
            tuple("production", savedProject().id()));
  }

  @Test
  void creatingAProjectIsAuditedWithItsCreatorAsTheActor() {
    service.create(owner, "storefront", "Storefront");

    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).record(event.capture());
    assertThat(event.getValue().action()).isEqualTo(AuditAction.PROJECT_CREATED);
    assertThat(event.getValue().projectId()).isEqualTo(savedProject().id());
    assertThat(event.getValue().actorId()).isEqualTo(owner.userId());
    assertThat(event.getValue().environmentId()).isNull();
    assertThat(event.getValue().previousValue()).isNull();
    assertThat(event.getValue().newValue())
        .containsEntry("key", "storefront")
        .containsEntry("environments", List.of("development", "staging", "production"));
  }

  @Test
  void aTakenKeyIsAConflictAndNothingElseIsWritten() {
    when(projects.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq"));

    assertThatExceptionOfType(ConflictException.class)
        .isThrownBy(() -> service.create(owner, "storefront", "Storefront"))
        .withMessage(ProjectService.KEY_TAKEN);
    verify(environments, never()).saveAll(any());
    verifyNoInteractions(audit);
  }

  @Test
  void listingReturnsTheOwnersProjects() {
    when(projects.findAll(owner))
        .thenReturn(List.of(new ProjectEntity(UUID.randomUUID(), owner.userId(), "a", "A", NOW)));

    assertThat(service.list(owner)).extracting(Project::key).containsExactly("a");
  }

  private ProjectEntity savedProject() {
    ArgumentCaptor<ProjectEntity> saved = ArgumentCaptor.forClass(ProjectEntity.class);
    verify(projects).saveAndFlush(saved.capture());
    return saved.getValue();
  }
}
