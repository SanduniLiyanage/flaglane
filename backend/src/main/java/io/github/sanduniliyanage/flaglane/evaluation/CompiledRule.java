package io.github.sanduniliyanage.flaglane.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A targeting rule checked once, when its ruleset is built, and ready to match on the hot path.
 *
 * <p>A rule the engine cannot apply — an unknown operator, no match values, a value that is not a
 * string, number or boolean, the wrong number of values for its operator — is kept as malformed
 * rather than dropped or rejected, so that the engine can answer for a flag whose evaluation
 * reaches it (FR-EVL-006) while the rest of the ruleset is unaffected.
 *
 * <p>Comparison semantics (FR-RUL-006 to FR-RUL-009, ADR-019):
 *
 * <ul>
 *   <li>An absent attribute never matches, for every operator including the negative ones.
 *   <li>Equality is type-strict and exact: {@code 1} never equals {@code "1"}, and strings compare
 *       case-sensitively with no normalisation.
 *   <li>For a present attribute, {@code NOT_EQUALS} and {@code NOT_IN} are exactly the negations of
 *       {@code EQUALS} and {@code IN}.
 *   <li>{@code CONTAINS}, {@code STARTS_WITH} and {@code ENDS_WITH} need a string on both sides and
 *       otherwise never match. They compare UTF-16 code units, as JavaScript's string methods do;
 *       for well-formed strings that is the same answer as comparing UTF-8 bytes.
 * </ul>
 */
final class CompiledRule {

  private final String attribute;
  private final Operator operator;
  private final Value operand;
  private final Set<Value> operands;
  private final boolean resultValue;
  private final String malformation;

  private CompiledRule(
      String attribute,
      Operator operator,
      Value operand,
      Set<Value> operands,
      boolean resultValue,
      String malformation) {
    this.attribute = attribute;
    this.operator = operator;
    this.operand = operand;
    this.operands = operands;
    this.resultValue = resultValue;
    this.malformation = malformation;
  }

  static CompiledRule compile(TargetingRule rule) {
    String problem = checkShape(rule);
    if (problem != null) {
      return malformed(rule, problem);
    }
    Operator operator = Operator.named(rule.operator()).orElseThrow();
    List<Value> values = new ArrayList<>(rule.matchValues().size());
    for (int i = 0; i < rule.matchValues().size(); i++) {
      Optional<Value> value = Value.from(rule.matchValues().get(i));
      if (value.isEmpty()) {
        return malformed(rule, "match value " + i + " is not a string, a number or a boolean");
      }
      values.add(value.get());
    }
    if (operator.shape() == Operator.Shape.SINGLE) {
      return new CompiledRule(
          rule.attribute(), operator, values.getFirst(), Set.of(), rule.resultValue(), null);
    }
    Class<?> type = values.getFirst().getClass();
    if (values.stream().anyMatch(value -> value.getClass() != type)) {
      return malformed(rule, operator + " match values are not all of one type");
    }
    return new CompiledRule(
        rule.attribute(), operator, null, Set.copyOf(values), rule.resultValue(), null);
  }

  private static String checkShape(TargetingRule rule) {
    if (rule.attribute() == null || rule.attribute().isEmpty()) {
      return "rule names no attribute";
    }
    Optional<Operator> operator = Operator.named(rule.operator());
    if (operator.isEmpty()) {
      return "unknown operator " + rule.operator();
    }
    if (rule.matchValues() == null || rule.matchValues().isEmpty()) {
      return "rule has no match values";
    }
    if (operator.get().shape() == Operator.Shape.SINGLE && rule.matchValues().size() != 1) {
      return operator.get() + " takes exactly one match value, has " + rule.matchValues().size();
    }
    return null;
  }

  private static CompiledRule malformed(TargetingRule rule, String problem) {
    return new CompiledRule(rule.attribute(), null, null, Set.of(), rule.resultValue(), problem);
  }

  boolean isMalformed() {
    return malformation != null;
  }

  /** Why the rule cannot be applied, or {@code null} when it can. */
  String malformation() {
    return malformation;
  }

  boolean resultValue() {
    return resultValue;
  }

  /** Whether the user matches. Only meaningful for a rule that is not malformed. */
  boolean matches(UserContext user) {
    Value actual = user.attribute(attribute);
    if (actual == null) {
      return false;
    }
    return switch (operator) {
      case EQUALS -> actual.equals(operand);
      case NOT_EQUALS -> !actual.equals(operand);
      case IN -> operands.contains(actual);
      case NOT_IN -> !operands.contains(actual);
      case CONTAINS -> bothStrings(actual) && text(actual).contains(text(operand));
      case STARTS_WITH -> bothStrings(actual) && text(actual).startsWith(text(operand));
      case ENDS_WITH -> bothStrings(actual) && text(actual).endsWith(text(operand));
    };
  }

  private boolean bothStrings(Value actual) {
    return actual instanceof StringValue && operand instanceof StringValue;
  }

  private static String text(Value value) {
    return ((StringValue) value).value();
  }
}
