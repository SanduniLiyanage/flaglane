package io.github.sanduniliyanage.flaglane.cluster.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * This running instance, made up when it starts, so it can tell its own announcements from other
 * instances' and pass over the ones it has already acted on (ADR-041).
 */
public record InstanceId(UUID value) {

  public InstanceId {
    Objects.requireNonNull(value, "value");
  }

  /** The first eight characters, enough to tell instances apart in a log or a connection name. */
  public String shortForm() {
    return value.toString().substring(0, 8);
  }
}
