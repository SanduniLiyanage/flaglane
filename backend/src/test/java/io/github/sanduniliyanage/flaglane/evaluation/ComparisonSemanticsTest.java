package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanduniliyanage.flaglane.evaluation.ComparisonSemanticsFixture.Case;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Suite 4a — comparison semantics (FR-RUL-006 to FR-RUL-009). Substantiates: the server and the SDK
 * agree where Java and JavaScript would naturally disagree. The rows live in the shared fixture,
 * which the TypeScript SDK runs too.
 */
class ComparisonSemanticsTest {

  static List<Case> cases() {
    return ComparisonSemanticsFixture.cases();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void ruleMatchesExactlyAsTheSharedFixtureSays(Case row) {
    CompiledRule rule = CompiledRule.compile(row.rule());

    String outcome =
        rule.isMalformed() ? "malformed" : rule.matches(row.user()) ? "match" : "no-match";

    assertThat(outcome).as(row.name()).isEqualTo(row.expected());
  }

  @Test
  void fixtureHasAnAbsentAttributeRowForEveryOperator() {
    Set<String> absent =
        cases().stream()
            .filter(row -> row.attributes().isEmpty() && !row.expected().equals("malformed"))
            .map(Case::operator)
            .collect(Collectors.toSet());

    assertThat(absent)
        .as("operators with an absent-attribute row")
        .containsExactlyInAnyOrder(
            Arrays.stream(Operator.values()).map(Enum::name).toArray(String[]::new));
  }

  @Test
  void malformedRuleSaysWhyItCannotBeApplied() {
    CompiledRule rule =
        CompiledRule.compile(new TargetingRule(0, "country", "EQUALS", List.of("LK", "US"), true));

    assertThat(rule.malformation()).isEqualTo("EQUALS takes exactly one match value, has 2");
  }

  @Test
  void wellFormedRuleHasNoMalformation() {
    assertThat(CompiledRule.compile(new TargetingRule(0, "country", "IN", List.of("LK"), true)))
        .satisfies(rule -> assertThat(rule.isMalformed()).isFalse())
        .satisfies(rule -> assertThat(rule.malformation()).isNull());
  }

  @Test
  void operatorNamesAreExactAndCaseSensitive() {
    assertThat(Operator.named("STARTS_WITH")).contains(Operator.STARTS_WITH);
    assertThat(Operator.named("starts_with")).isEmpty();
    assertThat(Operator.named("MATCHES_REGEX")).isEmpty();
    assertThat(Operator.named(null)).isEmpty();
  }
}
