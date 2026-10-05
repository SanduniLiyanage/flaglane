package io.github.sanduniliyanage.flaglane.serving.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The ruleset as {@code GET /sdk/config} serves it (docs/API.md). It carries basis points, not
 * percentages, so the SDK compares {@code bucket < rolloutBasisPoints} and never converts units.
 */
public record ServedRuleset(String environment, long version, List<Flag> flags) {

  public ServedRuleset {
    flags = List.copyOf(flags);
  }

  /**
   * One flag.
   *
   * @param offValue always {@code false} in v0.x, carried so that making it configurable later is a
   *     value change rather than a wire-format change
   * @param overrides {@code null} for a client key, and then absent from the JSON altogether: a
   *     client ruleset has no {@code overrides} field on any flag (FR-KEY-008)
   */
  public record Flag(
      String key,
      boolean enabled,
      boolean offValue,
      boolean fallthroughValue,
      int rolloutBasisPoints,
      String rolloutSalt,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<UserOverride> overrides,
      List<Rule> rules) {

    public Flag {
      overrides = overrides == null ? null : List.copyOf(overrides);
      rules = List.copyOf(rules);
    }
  }

  /** A user override. */
  public record UserOverride(String userKey, boolean value) {}

  /** A targeting rule, in priority order. */
  public record Rule(
      int priority,
      String attribute,
      String operator,
      List<Object> matchValues,
      boolean resultValue) {

    public Rule {
      matchValues = List.copyOf(matchValues);
    }
  }
}
