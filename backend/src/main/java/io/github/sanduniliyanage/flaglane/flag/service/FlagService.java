package io.github.sanduniliyanage.flaglane.flag.service;

import io.github.sanduniliyanage.flaglane.audit.domain.AuditAction;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditEvent;
import io.github.sanduniliyanage.flaglane.audit.service.AuditLog;
import io.github.sanduniliyanage.flaglane.common.errors.ConflictException;
import io.github.sanduniliyanage.flaglane.common.errors.NotFoundException;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import io.github.sanduniliyanage.flaglane.flag.domain.Flag;
import io.github.sanduniliyanage.flaglane.flag.domain.FlagChange;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagConfigEntity;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagConfigRepository;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagEntity;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagRepository;
import io.github.sanduniliyanage.flaglane.project.service.RulesetVersions;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Flags within a project: create, edit, archive, restore (FR-FLG-001 to FR-FLG-007). */
@Service
public class FlagService {

  static final String NOT_FOUND = "Flag not found";
  static final String KEY_TAKEN =
      "Flag key is already used in this project, by a live or an archived flag";
  static final String ARCHIVED = "Flag is archived; restore it to change it";

  private final FlagRepository flags;
  private final FlagConfigRepository configs;
  private final TenantResolver tenants;
  private final RulesetVersions versions;
  private final AuditLog audit;
  private final Clock clock;

  public FlagService(
      FlagRepository flags,
      FlagConfigRepository configs,
      TenantResolver tenants,
      RulesetVersions versions,
      AuditLog audit,
      Clock clock) {
    this.flags = flags;
    this.configs = configs;
    this.tenants = tenants;
    this.versions = versions;
    this.audit = audit;
    this.clock = clock;
  }

  /** Live and archived flags, by key. */
  @Transactional(readOnly = true)
  public List<Flag> list(ProjectScope project) {
    return flags.findAll(project).stream().map(FlagService::toFlag).toList();
  }

  @Transactional(readOnly = true)
  public Flag get(ProjectScope project, String flagKey) {
    return toFlag(find(project, flagKey));
  }

  /**
   * Creates a flag and its configuration in every environment of the project: disabled, falling
   * through to {@code false}, at 0% (FR-FLG-003).
   *
   * @throws ConflictException if the project has a flag with this key, archived ones included
   *     (FR-FLG-005)
   */
  @Transactional
  public Flag create(
      ProjectScope project,
      String key,
      String name,
      String description,
      boolean clientSideVisible) {
    Instant now = now();
    FlagEntity flag =
        new FlagEntity(
            UUID.randomUUID(),
            project.projectId(),
            key,
            name,
            blankToNull(description),
            clientSideVisible,
            now);
    try {
      flags.saveAndFlush(flag);
    } catch (DataIntegrityViolationException e) {
      throw new ConflictException(KEY_TAKEN, e);
    }
    configs.saveAll(
        tenants.environments(project).stream()
            .map(
                environment ->
                    FlagConfigEntity.newDefault(flag.id(), key, environment.environmentId(), now))
            .toList());
    audit.record(event(AuditAction.FLAG_CREATED, project, flag).next(describe(flag)));
    versions.changed(project);
    return toFlag(flag);
  }

  /**
   * Changes a flag's name, description or visibility. Visibility decides what client keys are
   * served, so changing it changes every environment's ruleset (FR-FLG-004).
   */
  @Transactional
  public Flag update(ProjectScope project, String flagKey, FlagChange change) {
    FlagEntity flag = findLive(project, flagKey);
    Map<String, Object> before = describe(flag);
    boolean visibilityChanged = false;
    if (change.name() != null) {
      flag.rename(change.name());
    }
    if (change.description() != null) {
      flag.describe(blankToNull(change.description()));
    }
    if (change.clientSideVisible() != null
        && change.clientSideVisible() != flag.isClientSideVisible()) {
      flag.setClientSideVisible(change.clientSideVisible());
      visibilityChanged = true;
    }
    Map<String, Object> after = describe(flag);
    if (!before.equals(after)) {
      audit.record(event(AuditAction.FLAG_UPDATED, project, flag).previous(before).next(after));
    }
    if (visibilityChanged) {
      versions.changed(project);
    }
    return toFlag(flag);
  }

  /**
   * Archives a flag: never a deletion, so its audit history keeps meaning and its key stays
   * reserved (FR-FLG-005). Archiving an archived flag changes nothing.
   */
  @Transactional
  public Flag archive(ProjectScope project, String flagKey) {
    FlagEntity flag = find(project, flagKey);
    if (flag.isArchived()) {
      return toFlag(flag);
    }
    Map<String, Object> before = describe(flag);
    flag.archive(now());
    audit.record(
        event(AuditAction.FLAG_ARCHIVED, project, flag).previous(before).next(describe(flag)));
    versions.changed(project);
    return toFlag(flag);
  }

  /**
   * Restores an archived flag with its key and configurations unchanged (FR-FLG-007). An
   * environment created while it was archived got no configuration for it (FR-ENV-004 covers live
   * flags only), so it gets the default one now; otherwise the restored flag would be missing from
   * that environment's ruleset. Restoring a live flag changes nothing.
   */
  @Transactional
  public Flag restore(ProjectScope project, String flagKey) {
    FlagEntity flag = find(project, flagKey);
    if (!flag.isArchived()) {
      return toFlag(flag);
    }
    Map<String, Object> before = describe(flag);
    flag.restore();
    Set<UUID> configured = Set.copyOf(configs.findEnvironmentsConfigured(project, flagKey));
    Instant now = now();
    configs.saveAll(
        tenants.environments(project).stream()
            .map(EnvironmentScope::environmentId)
            .filter(environment -> !configured.contains(environment))
            .map(environment -> FlagConfigEntity.newDefault(flag.id(), flagKey, environment, now))
            .toList());
    audit.record(
        event(AuditAction.FLAG_RESTORED, project, flag).previous(before).next(describe(flag)));
    versions.changed(project);
    return toFlag(flag);
  }

  private FlagEntity find(ProjectScope project, String flagKey) {
    return flags.find(project, flagKey).orElseThrow(() -> new NotFoundException(NOT_FOUND));
  }

  private FlagEntity findLive(ProjectScope project, String flagKey) {
    FlagEntity flag = find(project, flagKey);
    if (flag.isArchived()) {
      throw new ConflictException(ARCHIVED);
    }
    return flag;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static AuditEvent event(AuditAction action, ProjectScope project, FlagEntity flag) {
    return AuditEvent.of(action, project.projectId(), project.userId()).flag(flag.id());
  }

  private static Map<String, Object> describe(FlagEntity flag) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("key", flag.getKey());
    state.put("name", flag.getName());
    state.put("description", flag.getDescription());
    state.put("clientSideVisible", flag.isClientSideVisible());
    state.put("archivedAt", Objects.toString(flag.getArchivedAt(), null));
    return state;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static Flag toFlag(FlagEntity flag) {
    return new Flag(
        flag.getKey(),
        flag.getName(),
        flag.getDescription(),
        flag.isClientSideVisible(),
        flag.getArchivedAt(),
        flag.getCreatedAt());
  }
}
