package io.github.sanduniliyanage.flaglane.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One targeting rule as stored and served (FR-RUL-002): it matches when the named attribute
 * satisfies the operator against the match values, and then supplies its result value.
 *
 * <p>The operator and the match values are carried as received rather than parsed into types that
 * could not represent a bad one. Rules are validated when written (FR-RUL-010), but the engine
 * still has to give an answer for a rule that was not, and it can only do that if the rule reaches
 * it intact.
 *
 * @param priority evaluation order within the configuration, ascending, first match wins
 * @param attribute the user context attribute the rule tests
 * @param operator one of the seven operator names in FR-RUL-003, as a string
 * @param matchValues the operands, as decoded from JSON; may hold nulls, and may itself be null
 * @param resultValue what the flag evaluates to when the rule matches
 */
public record TargetingRule(
    int priority, String attribute, String operator, List<?> matchValues, boolean resultValue) {

  public TargetingRule {
    // Not List.copyOf: that rejects nulls, and a null entry is a malformed rule the engine must be
    // able to see rather than a construction failure.
    matchValues =
        matchValues == null ? null : Collections.unmodifiableList(new ArrayList<>(matchValues));
  }
}
