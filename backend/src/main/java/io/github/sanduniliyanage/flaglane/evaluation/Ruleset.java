package io.github.sanduniliyanage.flaglane.evaluation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The complete evaluation input for one environment: every flag configuration it serves, at one
 * {@code ruleset_version}. Immutable, so a snapshot can be swapped in atomically and read without
 * locks (ADR-008).
 */
public final class Ruleset {

  private final String environment;
  private final long version;
  private final Map<String, FlagConfig> flags;

  private Ruleset(String environment, long version, Map<String, FlagConfig> flags) {
    this.environment = environment;
    this.version = version;
    this.flags = flags;
  }

  /**
   * @throws IllegalArgumentException if two configurations share a flag key, which the schema makes
   *     impossible for one environment
   */
  public static Ruleset of(String environment, long version, Collection<FlagConfig> flags) {
    Objects.requireNonNull(environment, "environment");
    Map<String, FlagConfig> byKey = new LinkedHashMap<>();
    for (FlagConfig flag : flags) {
      if (byKey.putIfAbsent(flag.key(), flag) != null) {
        throw new IllegalArgumentException("Duplicate flag key in ruleset: " + flag.key());
      }
    }
    return new Ruleset(environment, version, Map.copyOf(byKey));
  }

  public static Ruleset empty(String environment, long version) {
    return of(environment, version, List.of());
  }

  public String environment() {
    return environment;
  }

  public long version() {
    return version;
  }

  /** The configuration for a flag key, or empty when this ruleset does not serve it. */
  public Optional<FlagConfig> flag(String key) {
    // Map.copyOf rejects a null lookup with an exception; an unknown key, null included, is
    // simply not here.
    return key == null ? Optional.empty() : Optional.ofNullable(flags.get(key));
  }

  public Collection<FlagConfig> flags() {
    return flags.values();
  }

  @Override
  public String toString() {
    return "Ruleset[environment="
        + environment
        + ", version="
        + version
        + ", flags="
        + flags.size()
        + "]";
  }
}
