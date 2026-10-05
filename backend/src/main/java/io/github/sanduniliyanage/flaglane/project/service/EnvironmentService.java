package io.github.sanduniliyanage.flaglane.project.service;

import io.github.sanduniliyanage.flaglane.audit.domain.AuditAction;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditEvent;
import io.github.sanduniliyanage.flaglane.audit.service.AuditLog;
import io.github.sanduniliyanage.flaglane.common.errors.ConflictException;
import io.github.sanduniliyanage.flaglane.common.errors.NotFoundException;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import io.github.sanduniliyanage.flaglane.project.domain.Environment;
import io.github.sanduniliyanage.flaglane.project.domain.EnvironmentCreated;
import io.github.sanduniliyanage.flaglane.project.persistence.EnvironmentEntity;
import io.github.sanduniliyanage.flaglane.project.persistence.EnvironmentRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Environments within a project: list, create, delete (FR-ENV-002 to FR-ENV-004). */
@Service
public class EnvironmentService {

  static final String KEY_TAKEN = "Environment key is already used in this project";
  static final String HAS_ACTIVE_KEYS =
      "Environment holds API keys that are not revoked; revoke them before deleting it";

  private final EnvironmentRepository environments;
  private final TenantResolver tenants;
  private final ApplicationEventPublisher events;
  private final AuditLog audit;
  private final Clock clock;

  public EnvironmentService(
      EnvironmentRepository environments,
      TenantResolver tenants,
      ApplicationEventPublisher events,
      AuditLog audit,
      Clock clock) {
    this.environments = environments;
    this.tenants = tenants;
    this.events = events;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<Environment> list(ProjectScope project) {
    return environments.findAll(project).stream().map(EnvironmentService::toEnvironment).toList();
  }

  /**
   * Creates an environment, and through {@link EnvironmentCreated} a configuration for every flag
   * the project already has, in the same transaction (FR-ENV-004).
   *
   * @throws ConflictException if the project already has an environment with this key
   */
  @Transactional
  public Environment create(ProjectScope project, String key, String name) {
    EnvironmentEntity environment =
        new EnvironmentEntity(
            UUID.randomUUID(),
            project.projectId(),
            key,
            name,
            clock.instant().truncatedTo(ChronoUnit.MICROS));
    try {
      environments.saveAndFlush(environment);
    } catch (DataIntegrityViolationException e) {
      throw new ConflictException(KEY_TAKEN, e);
    }
    events.publishEvent(new EnvironmentCreated(tenants.environment(project, key)));
    audit.record(
        AuditEvent.of(AuditAction.ENVIRONMENT_CREATED, project.projectId(), project.userId())
            .environment(environment.id())
            .next(describe(environment)));
    return toEnvironment(environment);
  }

  /**
   * Deletes an environment and, by cascade, its keys, configurations, rules and overrides. Its
   * audit entries are kept (ADR-022).
   *
   * @throws ConflictException while the environment holds a key that is not revoked (FR-ENV-003)
   */
  @Transactional
  public void delete(EnvironmentScope scope) {
    if (environments.countActiveKeys(scope) > 0) {
      throw new ConflictException(HAS_ACTIVE_KEYS);
    }
    EnvironmentEntity environment =
        environments.find(scope).orElseThrow(() -> new NotFoundException("Environment not found"));
    audit.record(
        AuditEvent.of(AuditAction.ENVIRONMENT_DELETED, scope.projectId(), scope.project().userId())
            .environment(scope.environmentId())
            .previous(describe(environment)));
    environments.delete(scope);
  }

  private static Map<String, Object> describe(EnvironmentEntity environment) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("key", environment.getKey());
    state.put("name", environment.getName());
    return state;
  }

  private static Environment toEnvironment(EnvironmentEntity environment) {
    return new Environment(environment.getKey(), environment.getName(), environment.getCreatedAt());
  }
}
