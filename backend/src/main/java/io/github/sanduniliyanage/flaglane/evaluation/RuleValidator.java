package io.github.sanduniliyanage.flaglane.evaluation;

import java.util.Optional;

/**
 * Whether a rule may be written (FR-RUL-010). Rules are validated when written, not when evaluated,
 * so a malformed rule cannot reach the serving path in the first place.
 *
 * <p>Everything the engine would treat as malformed is refused here, from the one definition the
 * engine itself uses (ADR-019), so the management API and the engine cannot drift apart. The write
 * is also stricter than evaluation in one respect: a non-string operand for {@code CONTAINS},
 * {@code STARTS_WITH} or {@code ENDS_WITH} is refused, where the engine, meeting one anyway, would
 * simply never match it (FR-RUL-009).
 */
public final class RuleValidator {

  private RuleValidator() {}

  /** Why the rule cannot be written, or empty when it can. */
  public static Optional<String> problem(TargetingRule rule) {
    CompiledRule compiled = CompiledRule.compile(rule);
    if (compiled.isMalformed()) {
      return Optional.of(compiled.malformation());
    }
    Operator operator = Operator.named(rule.operator()).orElseThrow();
    boolean stringOnly =
        operator == Operator.CONTAINS
            || operator == Operator.STARTS_WITH
            || operator == Operator.ENDS_WITH;
    if (stringOnly && !(rule.matchValues().getFirst() instanceof String)) {
      return Optional.of(operator + " compares strings only");
    }
    return Optional.empty();
  }
}
