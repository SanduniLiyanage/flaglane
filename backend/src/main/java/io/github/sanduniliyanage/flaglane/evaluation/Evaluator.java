package io.github.sanduniliyanage.flaglane.evaluation;

import java.util.Optional;

/**
 * The evaluation engine: {@code evaluate(Ruleset, flagKey, UserContext, fallback)}.
 *
 * <p>Resolution order, first match wins (FR-EVL-001):
 *
 * <ol>
 *   <li><b>Kill switch.</b> Disabled → the off value, {@code false}. Nothing else is consulted.
 *   <li><b>User override.</b> An exact match on the user key → its value.
 *   <li><b>Targeting rules.</b> Priority ascending, first match → its result value.
 *   <li><b>Percentage rollout.</b> {@code bucket < rolloutBasisPoints} → {@code true}.
 *   <li><b>Fallthrough.</b> The fallthrough value.
 * </ol>
 *
 * <p>Without a user key, steps 2 and 4 are skipped and rules still apply (FR-EVL-005). A malformed
 * rule that evaluation reaches resolves the flag to its fallthrough value (ADR-020).
 */
public final class Evaluator {

  /**
   * Evaluates one flag for one user.
   *
   * @param fallback what the caller has decided is safe; returned when the ruleset does not serve
   *     the flag (FR-EVL-007)
   */
  public Evaluation evaluate(Ruleset ruleset, String flagKey, UserContext user, boolean fallback) {
    Optional<FlagConfig> config = ruleset.flag(flagKey);
    if (config.isEmpty()) {
      return Evaluation.of(fallback, Reason.FLAG_NOT_FOUND);
    }
    return resolve(config.get(), user == null ? UserContext.anonymous() : user);
  }

  private static Evaluation resolve(FlagConfig config, UserContext user) {
    if (!config.enabled()) {
      return Evaluation.of(config.offValue(), Reason.OFF);
    }
    if (user.hasKey()) {
      Boolean override = config.overrides().get(user.key());
      if (override != null) {
        return Evaluation.of(override, Reason.OVERRIDE);
      }
    }
    for (CompiledRule rule : config.compiledRules()) {
      if (rule.isMalformed()) {
        return Evaluation.of(config.fallthroughValue(), Reason.ERROR);
      }
      if (rule.matches(user)) {
        return Evaluation.of(rule.resultValue(), Reason.RULE_MATCH);
      }
    }
    if (user.hasKey() && config.isInRollout(user.key())) {
      return Evaluation.of(true, Reason.ROLLOUT);
    }
    return Evaluation.of(config.fallthroughValue(), Reason.FALLTHROUGH);
  }
}
