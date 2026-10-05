package io.github.sanduniliyanage.flaglane.targeting.service;

import io.github.sanduniliyanage.flaglane.audit.domain.AuditAction;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditEvent;
import io.github.sanduniliyanage.flaglane.audit.service.AuditLog;
import io.github.sanduniliyanage.flaglane.common.errors.ConflictException;
import io.github.sanduniliyanage.flaglane.common.errors.NotFoundException;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.evaluation.RuleValidator;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagConfigEntity;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagConfigRepository;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagEntity;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagRepository;
import io.github.sanduniliyanage.flaglane.project.service.RulesetVersions;
import io.github.sanduniliyanage.flaglane.targeting.domain.Rule;
import io.github.sanduniliyanage.flaglane.targeting.domain.UserOverride;
import io.github.sanduniliyanage.flaglane.targeting.persistence.TargetingRuleEntity;
import io.github.sanduniliyanage.flaglane.targeting.persistence.TargetingRuleRepository;
import io.github.sanduniliyanage.flaglane.targeting.persistence.UserOverrideEntity;
import io.github.sanduniliyanage.flaglane.targeting.persistence.UserOverrideRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A configuration's targeting rules and user overrides, each replaced as a whole in one transaction
 * (FR-RUL-001 to FR-RUL-004). No intermediate state is ever visible, and none ever holds two rules
 * at one priority.
 */
@Service
public class TargetingService {

  static final String NOT_FOUND = "Flag not found";
  static final String ARCHIVED = "Flag is archived; restore it to change it";

  private final FlagRepository flags;
  private final FlagConfigRepository configs;
  private final TargetingRuleRepository rules;
  private final UserOverrideRepository overrides;
  private final RulesetVersions versions;
  private final AuditLog audit;

  public TargetingService(
      FlagRepository flags,
      FlagConfigRepository configs,
      TargetingRuleRepository rules,
      UserOverrideRepository overrides,
      RulesetVersions versions,
      AuditLog audit) {
    this.flags = flags;
    this.configs = configs;
    this.rules = rules;
    this.overrides = overrides;
    this.versions = versions;
    this.audit = audit;
  }

  /** In priority order. */
  @Transactional(readOnly = true)
  public List<Rule> rules(EnvironmentScope environment, String flagKey) {
    configuration(environment, flagKey);
    return rules.findAll(environment, flagKey).stream().map(TargetingService::toRule).toList();
  }

  @Transactional(readOnly = true)
  public List<UserOverride> overrides(EnvironmentScope environment, String flagKey) {
    configuration(environment, flagKey);
    return overrides.findAll(environment, flagKey).stream()
        .map(TargetingService::toOverride)
        .toList();
  }

  /**
   * Replaces the configuration's rules. Priorities are the list positions, from 0 with no gaps.
   *
   * @param replacement every rule must pass {@link RuleValidator}; the web layer has checked
   * @throws ConflictException if the flag is archived
   */
  @Transactional
  public List<Rule> replaceRules(
      EnvironmentScope environment, String flagKey, List<Rule> replacement) {
    FlagConfigEntity config = liveConfiguration(environment, flagKey);
    for (int i = 0; i < replacement.size(); i++) {
      RuleValidator.problem(replacement.get(i).toTargetingRule(i))
          .ifPresent(
              problem -> {
                throw new IllegalArgumentException(problem);
              });
    }
    List<Rule> before = rules(environment, flagKey);
    if (before.equals(replacement)) {
      return before;
    }
    rules.deleteAll(environment, flagKey);
    rules.saveAll(
        IntStream.range(0, replacement.size())
            .mapToObj(
                priority -> {
                  Rule rule = replacement.get(priority);
                  return new TargetingRuleEntity(
                      UUID.randomUUID(),
                      config.id(),
                      priority,
                      rule.attribute(),
                      rule.operator(),
                      rule.matchValues(),
                      rule.resultValue());
                })
            .toList());
    audit.record(
        event(AuditAction.RULES_REPLACED, environment, config)
            .previous(Map.of("rules", before.stream().map(Rule::describe).toList()))
            .next(Map.of("rules", replacement.stream().map(Rule::describe).toList())));
    versions.changed(environment);
    return List.copyOf(replacement);
  }

  /**
   * Replaces the configuration's overrides.
   *
   * @param replacement one entry per user key; the web layer has checked
   * @throws ConflictException if the flag is archived
   */
  @Transactional
  public List<UserOverride> replaceOverrides(
      EnvironmentScope environment, String flagKey, List<UserOverride> replacement) {
    FlagConfigEntity config = liveConfiguration(environment, flagKey);
    List<UserOverride> before = overrides(environment, flagKey);
    List<UserOverride> sorted =
        replacement.stream().sorted(Comparator.comparing(UserOverride::userKey)).toList();
    if (before.equals(sorted)) {
      return before;
    }
    overrides.deleteAll(environment, flagKey);
    overrides.saveAll(
        sorted.stream()
            .map(
                override ->
                    new UserOverrideEntity(
                        UUID.randomUUID(), config.id(), override.userKey(), override.value()))
            .toList());
    audit.record(
        event(AuditAction.OVERRIDES_REPLACED, environment, config)
            .previous(Map.of("overrides", before.stream().map(UserOverride::describe).toList()))
            .next(Map.of("overrides", sorted.stream().map(UserOverride::describe).toList())));
    versions.changed(environment);
    return sorted;
  }

  private FlagConfigEntity liveConfiguration(EnvironmentScope environment, String flagKey) {
    FlagEntity flag =
        flags
            .find(environment.project(), flagKey)
            .orElseThrow(() -> new NotFoundException(NOT_FOUND));
    if (flag.isArchived()) {
      throw new ConflictException(ARCHIVED);
    }
    return configuration(environment, flagKey);
  }

  private FlagConfigEntity configuration(EnvironmentScope environment, String flagKey) {
    return configs.find(environment, flagKey).orElseThrow(() -> new NotFoundException(NOT_FOUND));
  }

  private static AuditEvent event(
      AuditAction action, EnvironmentScope environment, FlagConfigEntity config) {
    return AuditEvent.of(action, environment.projectId(), environment.project().userId())
        .environment(environment.environmentId())
        .flag(config.getFlagId());
  }

  private static Rule toRule(TargetingRuleEntity rule) {
    return new Rule(
        rule.getAttribute(), rule.getOperator(), rule.getMatchValues(), rule.getResultValue());
  }

  private static UserOverride toOverride(UserOverrideEntity override) {
    return new UserOverride(override.getUserKey(), override.getValue());
  }
}
