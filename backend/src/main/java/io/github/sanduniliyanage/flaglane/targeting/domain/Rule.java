package io.github.sanduniliyanage.flaglane.targeting.domain;

import io.github.sanduniliyanage.flaglane.evaluation.TargetingRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A targeting rule as written (FR-RUL-002). Its priority is its position in the configuration's
 * list, assigned when the list is replaced (FR-RUL-004).
 *
 * @param matchValues strings, numbers or booleans, as decoded from JSON
 */
public record Rule(
    String attribute, String operator, List<Object> matchValues, boolean resultValue) {

  public Rule {
    matchValues = Collections.unmodifiableList(new ArrayList<>(matchValues));
  }

  /** The rule as the engine reads it, at the given priority. */
  public TargetingRule toTargetingRule(int priority) {
    return new TargetingRule(priority, attribute, operator, matchValues, resultValue);
  }

  /** As the audit trail records it. */
  public Map<String, Object> describe() {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("attribute", attribute);
    state.put("operator", operator);
    state.put("matchValues", matchValues);
    state.put("resultValue", resultValue);
    return state;
  }
}
