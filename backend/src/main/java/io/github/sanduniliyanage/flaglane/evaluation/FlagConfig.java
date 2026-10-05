package io.github.sanduniliyanage.flaglane.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One flag's configuration in one environment: everything the engine needs to evaluate it
 * (FR-FLG-006). Immutable.
 *
 * <p>There is deliberately no way to set the off value. A disabled configuration returns {@code
 * false} in v0.x, and no setting may make disabling a flag turn a feature on (ADR-009).
 */
public final class FlagConfig {

  /** The largest rollout: every bucket, 0 to 9999, is below it (ADR-011). */
  public static final int MAX_ROLLOUT_BASIS_POINTS = 10_000;

  private final String key;
  private final boolean enabled;
  private final boolean fallthroughValue;
  private final int rolloutBasisPoints;
  private final String rolloutSalt;
  private final Map<String, Boolean> overrides;
  private final List<TargetingRule> rules;

  private FlagConfig(Builder builder) {
    if (builder.rolloutBasisPoints < 0 || builder.rolloutBasisPoints > MAX_ROLLOUT_BASIS_POINTS) {
      throw new IllegalArgumentException(
          "rolloutBasisPoints must be 0 to 10000, was " + builder.rolloutBasisPoints);
    }
    this.key = builder.key;
    this.enabled = builder.enabled;
    this.fallthroughValue = builder.fallthroughValue;
    this.rolloutBasisPoints = builder.rolloutBasisPoints;
    this.rolloutSalt = builder.rolloutSalt == null ? builder.key : builder.rolloutSalt;
    this.overrides = Map.copyOf(builder.overrides);
    List<TargetingRule> sorted = new ArrayList<>(builder.rules);
    // Stable, so rules sharing a priority keep the order they arrived in, as Array.sort does.
    sorted.sort(Comparator.comparingInt(TargetingRule::priority));
    this.rules = List.copyOf(sorted);
  }

  /**
   * A configuration as FR-FLG-003 creates one: disabled, falling through to {@code false}, at 0%
   * rollout, with no rules and no overrides, salted with its own key (ADR-010).
   */
  public static Builder builder(String key) {
    return new Builder(key);
  }

  /** A builder holding this configuration's values, for deriving a changed copy. */
  public Builder toBuilder() {
    Builder builder =
        new Builder(key)
            .enabled(enabled)
            .fallthroughValue(fallthroughValue)
            .rolloutBasisPoints(rolloutBasisPoints)
            .rolloutSalt(rolloutSalt);
    overrides.forEach(builder::override);
    rules.forEach(builder::rule);
    return builder;
  }

  public String key() {
    return key;
  }

  public boolean enabled() {
    return enabled;
  }

  /** Fixed {@code false} in v0.x (ADR-009). */
  public boolean offValue() {
    return false;
  }

  public boolean fallthroughValue() {
    return fallthroughValue;
  }

  public int rolloutBasisPoints() {
    return rolloutBasisPoints;
  }

  public String rolloutSalt() {
    return rolloutSalt;
  }

  /**
   * Whether the percentage rollout reaches this user: {@code bucket(rolloutSalt, userKey) <
   * rolloutBasisPoints} (FR-EVL-001 step 4).
   */
  public boolean isInRollout(String userKey) {
    return Bucketing.isInRollout(rolloutSalt, userKey, rolloutBasisPoints);
  }

  /** User overrides by user key. */
  public Map<String, Boolean> overrides() {
    return overrides;
  }

  /** Targeting rules in evaluation order: priority ascending. */
  public List<TargetingRule> rules() {
    return rules;
  }

  @Override
  public String toString() {
    return "FlagConfig[key=" + key + ", enabled=" + enabled + "]";
  }

  /** Builds a {@link FlagConfig}. Defaults are those of a newly created flag (FR-FLG-003). */
  public static final class Builder {

    private final String key;
    private boolean enabled;
    private boolean fallthroughValue;
    private int rolloutBasisPoints;
    private String rolloutSalt;
    private final Map<String, Boolean> overrides = new LinkedHashMap<>();
    private final List<TargetingRule> rules = new ArrayList<>();

    private Builder(String key) {
      this.key = Objects.requireNonNull(key, "key");
    }

    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    public Builder fallthroughValue(boolean fallthroughValue) {
      this.fallthroughValue = fallthroughValue;
      return this;
    }

    public Builder rolloutBasisPoints(int rolloutBasisPoints) {
      this.rolloutBasisPoints = rolloutBasisPoints;
      return this;
    }

    public Builder rolloutSalt(String rolloutSalt) {
      this.rolloutSalt = Objects.requireNonNull(rolloutSalt, "rolloutSalt");
      return this;
    }

    public Builder override(String userKey, boolean value) {
      overrides.put(Objects.requireNonNull(userKey, "userKey"), value);
      return this;
    }

    public Builder rule(TargetingRule rule) {
      rules.add(Objects.requireNonNull(rule, "rule"));
      return this;
    }

    public FlagConfig build() {
      return new FlagConfig(this);
    }
  }
}
