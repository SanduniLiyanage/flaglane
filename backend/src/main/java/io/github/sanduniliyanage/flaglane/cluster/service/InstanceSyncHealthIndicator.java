package io.github.sanduniliyanage.flaglane.cluster.service;

import io.github.sanduniliyanage.flaglane.cluster.domain.InstanceId;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code instanceSync} on {@code /actuator/health}: whether this instance hears other instances'
 * changes (ADR-041). Like the database's, it gates nothing (NFR-REL-003): an instance that does not
 * hear the others still serves, from rulesets at most a minute behind them.
 */
@Component("instanceSync")
@ConditionalOnProperty(prefix = "flaglane.instance-sync", name = "enabled", havingValue = "true")
public class InstanceSyncHealthIndicator implements HealthIndicator {

  private final ChangeListener listener;
  private final InstanceId instance;

  public InstanceSyncHealthIndicator(ChangeListener listener, InstanceId instance) {
    this.listener = listener;
    this.instance = instance;
  }

  @Override
  public Health health() {
    Health.Builder health = listener.isListening() ? Health.up() : Health.down();
    return health.withDetail("instance", instance.shortForm()).build();
  }
}
