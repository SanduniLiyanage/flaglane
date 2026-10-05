package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanduniliyanage.flaglane.evaluation.ComparisonSemanticsFixture.Case;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** FR-RUL-010: the write-time check refuses exactly what the engine cannot apply, and more. */
class RuleValidatorTest {

  static List<Case> cases() {
    return ComparisonSemanticsFixture.cases();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void everyRuleTheEngineCallsMalformedIsRefusedOnWrite(Case row) {
    boolean malformed = row.expected().equals("malformed");

    assertThat(RuleValidator.problem(row.rule()).isPresent())
        .as(row.name())
        .isEqualTo(malformed || stringOperatorWithANonString(row));
  }

  @Test
  void aStringOperatorWithANumberOperandIsRefusedOnWriteThoughTheEngineWouldOnlyNeverMatch() {
    TargetingRule rule = new TargetingRule(0, "plan", "CONTAINS", List.of(1), true);

    assertThat(RuleValidator.problem(rule)).contains("CONTAINS compares strings only");
  }

  @Test
  void aWellFormedRuleHasNoProblem() {
    assertThat(
            RuleValidator.problem(new TargetingRule(0, "country", "IN", List.of("LK", "IN"), true)))
        .isEmpty();
  }

  private static boolean stringOperatorWithANonString(Case row) {
    return List.of("CONTAINS", "STARTS_WITH", "ENDS_WITH").contains(row.operator())
        && row.matchValues() != null
        && row.matchValues().size() == 1
        && !(row.matchValues().getFirst() instanceof String);
  }
}
