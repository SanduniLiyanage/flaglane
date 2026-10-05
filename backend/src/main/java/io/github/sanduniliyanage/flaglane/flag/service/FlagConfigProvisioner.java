package io.github.sanduniliyanage.flaglane.flag.service;

import io.github.sanduniliyanage.flaglane.flag.persistence.FlagConfigEntity;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagConfigRepository;
import io.github.sanduniliyanage.flaglane.flag.persistence.FlagRepository;
import io.github.sanduniliyanage.flaglane.project.domain.EnvironmentCreated;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Gives a new environment a configuration for every live flag the project already has, in the
 * transaction that creates the environment (FR-ENV-004): disabled, falling through to {@code
 * false}, at 0%. Without this a new environment serves an empty ruleset, every flag falls through
 * to the SDK's code-level fallback, and the result looks exactly like a working system.
 */
@Component
public class FlagConfigProvisioner {

  private final FlagRepository flags;
  private final FlagConfigRepository configs;
  private final Clock clock;

  public FlagConfigProvisioner(FlagRepository flags, FlagConfigRepository configs, Clock clock) {
    this.flags = flags;
    this.configs = configs;
    this.clock = clock;
  }

  /** Synchronous, so it runs inside the creating transaction and a failure rolls it back. */
  @EventListener
  public void environmentCreated(EnvironmentCreated event) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    configs.saveAll(
        flags.findLive(event.environment().project()).stream()
            .map(
                flag ->
                    FlagConfigEntity.newDefault(
                        flag.id(), flag.getKey(), event.environment().environmentId(), now))
            .toList());
  }
}
