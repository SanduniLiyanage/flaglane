package io.github.sanduniliyanage.flaglane.serving.service;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * The one thing readiness depends on: the ruleset cache has been built (NFR-REL-003). It reads the
 * cache, never the database, so a database outage cannot take an instance out of rotation while it
 * can still serve every SDK from memory. Reported as {@code rulesetCache}.
 */
@Component
public class RulesetCacheHealthIndicator implements HealthIndicator {

  private final RulesetCache cache;

  public RulesetCacheHealthIndicator(RulesetCache cache) {
    this.cache = cache;
  }

  @Override
  public Health health() {
    if (!cache.isReady()) {
      return Health.outOfService().build();
    }
    return Health.up().withDetail("environments", cache.size()).build();
  }
}
