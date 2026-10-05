package io.github.sanduniliyanage.flaglane.project.domain;

import java.util.Set;
import java.util.UUID;

/**
 * Published inside a transaction that changed what some environments serve, after their {@code
 * ruleset_version} was bumped in it. Whatever holds a copy of those rulesets rebuilds it once the
 * transaction commits (ADR-008).
 */
public record RulesetChanged(Set<UUID> environmentIds) {

  public RulesetChanged {
    environmentIds = Set.copyOf(environmentIds);
  }
}
