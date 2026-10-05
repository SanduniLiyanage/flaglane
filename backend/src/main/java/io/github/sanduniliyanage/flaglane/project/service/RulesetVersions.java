package io.github.sanduniliyanage.flaglane.project.service;

import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import io.github.sanduniliyanage.flaglane.project.domain.RulesetChanged;
import io.github.sanduniliyanage.flaglane.project.persistence.EnvironmentRepository;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bumps {@code environments.ruleset_version} in the transaction of every write that changes what an
 * environment serves (docs/DATABASE.md), and announces it. {@link Propagation#MANDATORY} for the
 * same reason as the audit log: a version bumped apart from its change would make an ETag claim a
 * change that did not commit, or miss one that did.
 */
@Service
public class RulesetVersions {

  private final EnvironmentRepository environments;
  private final TenantResolver tenants;
  private final ApplicationEventPublisher events;

  public RulesetVersions(
      EnvironmentRepository environments,
      TenantResolver tenants,
      ApplicationEventPublisher events) {
    this.environments = environments;
    this.tenants = tenants;
    this.events = events;
  }

  /** A configuration, rule or override change: one environment. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void changed(EnvironmentScope environment) {
    environments.bumpRulesetVersion(environment);
    events.publishEvent(new RulesetChanged(Set.of(environment.environmentId())));
  }

  /** A change to a flag itself — creation, visibility, archive, restore: every environment. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void changed(ProjectScope project) {
    environments.bumpRulesetVersions(project);
    events.publishEvent(
        new RulesetChanged(
            tenants.environments(project).stream()
                .map(EnvironmentScope::environmentId)
                .collect(Collectors.toSet())));
  }
}
