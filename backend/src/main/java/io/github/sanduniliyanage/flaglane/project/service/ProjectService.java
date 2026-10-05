package io.github.sanduniliyanage.flaglane.project.service;

import io.github.sanduniliyanage.flaglane.audit.domain.AuditAction;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditEvent;
import io.github.sanduniliyanage.flaglane.audit.service.AuditLog;
import io.github.sanduniliyanage.flaglane.common.errors.ConflictException;
import io.github.sanduniliyanage.flaglane.common.tenancy.OwnerScope;
import io.github.sanduniliyanage.flaglane.project.domain.Project;
import io.github.sanduniliyanage.flaglane.project.persistence.EnvironmentEntity;
import io.github.sanduniliyanage.flaglane.project.persistence.EnvironmentRepository;
import io.github.sanduniliyanage.flaglane.project.persistence.ProjectEntity;
import io.github.sanduniliyanage.flaglane.project.persistence.ProjectRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Projects: listing the caller's own, and creating one with its default environments. */
@Service
public class ProjectService {

  /** FR-ENV-001, in the order a dashboard shows them. */
  static final List<Map.Entry<String, String>> DEFAULT_ENVIRONMENTS =
      List.of(
          Map.entry("development", "Development"),
          Map.entry("staging", "Staging"),
          Map.entry("production", "Production"));

  static final String KEY_TAKEN = "Project key is already taken";

  private final ProjectRepository projects;
  private final EnvironmentRepository environments;
  private final AuditLog audit;
  private final Clock clock;

  public ProjectService(
      ProjectRepository projects, EnvironmentRepository environments, AuditLog audit, Clock clock) {
    this.projects = projects;
    this.environments = environments;
    this.audit = audit;
    this.clock = clock;
  }

  /** The caller's projects only (FR-PRJ-003). */
  @Transactional(readOnly = true)
  public List<Project> list(OwnerScope owner) {
    return projects.findAll(owner).stream().map(ProjectService::toProject).toList();
  }

  /**
   * Creates a project and its development, staging and production environments in one transaction,
   * with its audit entry (FR-PRJ-001, FR-ENV-001, FR-AUD-001).
   *
   * @throws ConflictException if the key is taken, by anyone: project keys are unique across the
   *     system (FR-PRJ-002), so this is the one answer that reveals another owner's project exists
   */
  @Transactional
  public Project create(OwnerScope owner, String key, String name) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    ProjectEntity project = new ProjectEntity(UUID.randomUUID(), owner.userId(), key, name, now);
    try {
      projects.saveAndFlush(project);
    } catch (DataIntegrityViolationException e) {
      throw new ConflictException(KEY_TAKEN, e);
    }
    environments.saveAll(
        DEFAULT_ENVIRONMENTS.stream()
            .map(
                environment ->
                    new EnvironmentEntity(
                        UUID.randomUUID(),
                        project.id(),
                        environment.getKey(),
                        environment.getValue(),
                        now))
            .toList());
    Map<String, Object> created = new LinkedHashMap<>();
    created.put("key", key);
    created.put("name", name);
    created.put("environments", DEFAULT_ENVIRONMENTS.stream().map(Map.Entry::getKey).toList());
    audit.record(
        AuditEvent.of(AuditAction.PROJECT_CREATED, project.id(), owner.userId()).next(created));
    return toProject(project);
  }

  private static Project toProject(ProjectEntity project) {
    return new Project(project.getKey(), project.getName(), project.getCreatedAt());
  }
}
