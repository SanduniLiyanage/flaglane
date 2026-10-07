package io.github.sanduniliyanage.flaglane.flag.service;

import io.github.sanduniliyanage.flaglane.audit.domain.AuditAction;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditEvent;
import io.github.sanduniliyanage.flaglane.audit.service.AuditLog;
import io.github.sanduniliyanage.flaglane.common.errors.ConflictException;
import io.github.sanduniliyanage.flaglane.common.errors.NotFoundException;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.flag.domain.ConfigChange;
import io.github.sanduniliyanage.flaglane.flag.domain.FlagConfiguration;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagConfigEntity;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagConfigRepository;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagEntity;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagRepository;
import io.github.sanduniliyanage.flaglane.project.service.RulesetVersions;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A flag's configuration in one environment: the kill switch, fallthrough and rollout. */
@Service
public class FlagConfigService {

  static final String NOT_FOUND = "Flag not found";

  private final FlagConfigRepository configs;
  private final FlagRepository flags;
  private final RulesetVersions versions;
  private final AuditLog audit;
  private final Clock clock;

  public FlagConfigService(
      FlagConfigRepository configs,
      FlagRepository flags,
      RulesetVersions versions,
      AuditLog audit,
      Clock clock) {
    this.configs = configs;
    this.flags = flags;
    this.versions = versions;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public FlagConfiguration get(EnvironmentScope environment, String flagKey) {
    return toConfiguration(find(environment, flagKey), flagKey, environment);
  }

  /** Every flag's configuration in the environment, archived flags included, by flag key. */
  @Transactional(readOnly = true)
  public List<FlagConfiguration> list(EnvironmentScope environment) {
    return configs.findAll(environment).stream()
        .map(
            row ->
                new FlagConfiguration(
                    row.flagKey(),
                    environment.environmentKey(),
                    row.enabled(),
                    row.offValue(),
                    row.fallthroughValue(),
                    row.rolloutBasisPoints(),
                    row.rolloutSalt(),
                    row.updatedAt()))
        .toList();
  }

  /**
   * Applies a change to one environment's configuration. Takes effect on the next ruleset this
   * environment serves; changes nothing, and records nothing, if every field already has the value
   * given.
   *
   * @throws ConflictException if the flag is archived
   */
  @Transactional
  public FlagConfiguration update(
      EnvironmentScope environment, String flagKey, ConfigChange change) {
    FlagEntity flag =
        flags
            .find(environment.project(), flagKey)
            .orElseThrow(() -> new NotFoundException(NOT_FOUND));
    if (flag.isArchived()) {
      throw new ConflictException(FlagService.ARCHIVED);
    }
    FlagConfigEntity config = find(environment, flagKey);
    Map<String, Object> before = describe(config);
    if (change.enabled() != null) {
      config.setEnabled(change.enabled());
    }
    if (change.fallthroughValue() != null) {
      config.setFallthroughValue(change.fallthroughValue());
    }
    if (change.rolloutBasisPoints() != null) {
      config.setRolloutBasisPoints(change.rolloutBasisPoints());
    }
    if (change.rolloutSalt() != null) {
      config.setRolloutSalt(change.rolloutSalt());
    }
    Map<String, Object> after = describe(config);
    if (!before.equals(after)) {
      config.touch(clock.instant().truncatedTo(ChronoUnit.MICROS));
      audit.record(
          AuditEvent.of(
                  AuditAction.CONFIG_UPDATED,
                  environment.projectId(),
                  environment.project().userId())
              .environment(environment.environmentId())
              .flag(config.getFlagId())
              .previous(before)
              .next(after));
      versions.changed(environment);
    }
    return toConfiguration(config, flagKey, environment);
  }

  private FlagConfigEntity find(EnvironmentScope environment, String flagKey) {
    return configs.find(environment, flagKey).orElseThrow(() -> new NotFoundException(NOT_FOUND));
  }

  private static Map<String, Object> describe(FlagConfigEntity config) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("enabled", config.isEnabled());
    state.put("fallthroughValue", config.getFallthroughValue());
    state.put("rolloutBasisPoints", config.getRolloutBasisPoints());
    state.put("rolloutSalt", config.getRolloutSalt());
    return state;
  }

  private static FlagConfiguration toConfiguration(
      FlagConfigEntity config, String flagKey, EnvironmentScope environment) {
    return new FlagConfiguration(
        flagKey,
        environment.environmentKey(),
        config.isEnabled(),
        config.getOffValue(),
        config.getFallthroughValue(),
        config.getRolloutBasisPoints(),
        config.getRolloutSalt(),
        config.getUpdatedAt());
  }
}
