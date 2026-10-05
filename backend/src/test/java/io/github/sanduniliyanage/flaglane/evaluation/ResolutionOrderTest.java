package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Suite 4 — resolution order (FR-EVL-001, FR-EVL-005). One row per branch, no exceptions: every
 * step beating every step below it, including a user at 0% reaching a flag through an override, and
 * the case behind ADR-009, a disabled flag whose fallthrough is {@code true}.
 */
class ResolutionOrderTest {

  private static final String FLAG = "new-checkout";

  /** "user-1" falls in bucket 631 for the salt "new-checkout" (see BucketingTest). */
  private static final String USER = "user-1";

  private static final TargetingRule PRO_GETS_TRUE = rule(0, "plan", "EQUALS", "pro", true);
  private static final TargetingRule PRO_GETS_FALSE = rule(0, "plan", "EQUALS", "pro", false);
  private static final UserContext PRO_USER =
      new UserContext(USER, Map.of("plan", Value.of("pro")));

  static Stream<Arguments> rows() {
    return Stream.of(
        row(
            "kill switch beats an override",
            flag().override(USER, true),
            UserContext.of(USER),
            false,
            Reason.OFF),
        row("kill switch beats a rule", flag().rule(PRO_GETS_TRUE), PRO_USER, false, Reason.OFF),
        row(
            "kill switch beats a full rollout",
            flag().rolloutBasisPoints(10_000),
            UserContext.of(USER),
            false,
            Reason.OFF),
        row(
            "kill switch beats a true fallthrough (ADR-009)",
            flag().fallthroughValue(true),
            UserContext.of(USER),
            false,
            Reason.OFF),
        row(
            "override beats a rule",
            enabled().override(USER, false).rule(PRO_GETS_TRUE),
            PRO_USER,
            false,
            Reason.OVERRIDE),
        row(
            "override beats a full rollout",
            enabled().override(USER, false).rolloutBasisPoints(10_000),
            UserContext.of(USER),
            false,
            Reason.OVERRIDE),
        row(
            "override beats a true fallthrough",
            enabled().override(USER, false).fallthroughValue(true),
            UserContext.of(USER),
            false,
            Reason.OVERRIDE),
        row(
            "user at 0% rollout receives the flag through an override",
            enabled().override(USER, true), UserContext.of(USER), true, Reason.OVERRIDE),
        row(
            "override for another user does not apply",
            enabled().override("someone-else", true),
            UserContext.of(USER),
            false,
            Reason.FALLTHROUGH),
        row(
            "rule beats a full rollout",
            enabled().rule(PRO_GETS_FALSE).rolloutBasisPoints(10_000),
            PRO_USER,
            false,
            Reason.RULE_MATCH),
        row(
            "rule beats a true fallthrough",
            enabled().rule(PRO_GETS_FALSE).fallthroughValue(true),
            PRO_USER,
            false,
            Reason.RULE_MATCH),
        row(
            "first matching rule by priority wins, whatever order the rules arrived in",
            enabled()
                .rule(rule(1, "plan", "EQUALS", "pro", true))
                .rule(rule(0, "plan", "EQUALS", "pro", false)),
            PRO_USER,
            false,
            Reason.RULE_MATCH),
        row(
            "a rule that does not match passes to the next",
            enabled()
                .rule(rule(0, "plan", "EQUALS", "free", false))
                .rule(rule(1, "plan", "EQUALS", "pro", true)),
            PRO_USER,
            true,
            Reason.RULE_MATCH),
        row(
            "rollout beats fallthrough",
            enabled().rolloutBasisPoints(10_000),
            UserContext.of(USER),
            true,
            Reason.ROLLOUT),
        row(
            "rollout reaches a user whose bucket is below it",
            enabled().rolloutBasisPoints(632),
            UserContext.of(USER),
            true,
            Reason.ROLLOUT),
        row(
            "rollout does not reach a user whose bucket equals it",
            enabled().rolloutBasisPoints(631),
            UserContext.of(USER),
            false,
            Reason.FALLTHROUGH),
        row(
            "fallthrough when nothing applies",
            enabled(),
            UserContext.of(USER),
            false,
            Reason.FALLTHROUGH),
        row(
            "a true fallthrough makes the rollout inert",
            enabled().fallthroughValue(true),
            UserContext.of(USER),
            true,
            Reason.FALLTHROUGH),
        row(
            "without a user key, overrides are skipped",
            enabled().override(USER, true),
            UserContext.anonymous(),
            false,
            Reason.FALLTHROUGH),
        row(
            "without a user key, rules still apply (FR-EVL-005)",
            enabled().rule(PRO_GETS_TRUE),
            new UserContext(null, Map.of("plan", Value.of("pro"))),
            true,
            Reason.RULE_MATCH),
        row(
            "without a user key, the rollout is skipped",
            enabled().rolloutBasisPoints(10_000),
            UserContext.anonymous(),
            false,
            Reason.FALLTHROUGH),
        row(
            "an empty user key is no user key (ADR-017)",
            enabled().override("", true).rolloutBasisPoints(10_000),
            UserContext.of(""),
            false,
            Reason.FALLTHROUGH),
        row(
            "a null context evaluates as anonymous",
            enabled().rolloutBasisPoints(10_000).fallthroughValue(false),
            null,
            false,
            Reason.FALLTHROUGH),
        row(
            "a malformed rule that evaluation reaches resolves to the fallthrough (ADR-020)",
            enabled()
                .fallthroughValue(true)
                .rule(new TargetingRule(0, "plan", "MATCHES_REGEX", List.of("^p"), false))
                .rolloutBasisPoints(10_000),
            PRO_USER,
            true,
            Reason.ERROR),
        row(
            "a malformed rule after a matching rule is never reached",
            enabled()
                .rule(PRO_GETS_TRUE)
                .rule(new TargetingRule(1, "plan", "MATCHES_REGEX", List.of("^p"), false)),
            PRO_USER,
            true,
            Reason.RULE_MATCH),
        row(
            "an override beats a malformed rule",
            enabled()
                .override(USER, true)
                .rule(new TargetingRule(0, "plan", "MATCHES_REGEX", List.of("^p"), false)),
            PRO_USER,
            true,
            Reason.OVERRIDE));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rows")
  void resolvesInOrder(
      FlagConfig.Builder config, UserContext user, boolean expectedValue, Reason expectedReason) {
    Ruleset ruleset = Ruleset.of("production", 1, List.of(config.build()));

    Evaluation evaluation = new Evaluator().evaluate(ruleset, FLAG, user, !expectedValue);

    assertThat(evaluation).isEqualTo(new Evaluation(expectedValue, expectedReason));
  }

  @ParameterizedTest(name = "fallback {0}")
  @MethodSource("fallbacks")
  void unknownFlagReturnsTheCallersFallback(boolean fallback) {
    Ruleset ruleset =
        Ruleset.of("production", 1, List.of(enabled().fallthroughValue(true).build()));

    Evaluation evaluation =
        new Evaluator().evaluate(ruleset, "spelled-wrong", UserContext.of(USER), fallback);

    assertThat(evaluation).isEqualTo(new Evaluation(fallback, Reason.FLAG_NOT_FOUND));
  }

  static Stream<Boolean> fallbacks() {
    return Stream.of(true, false);
  }

  private static FlagConfig.Builder flag() {
    return FlagConfig.builder(FLAG);
  }

  private static FlagConfig.Builder enabled() {
    return flag().enabled(true);
  }

  private static TargetingRule rule(
      int priority, String attribute, String operator, Object value, boolean result) {
    return new TargetingRule(priority, attribute, operator, List.of(value), result);
  }

  private static Arguments row(
      String name,
      FlagConfig.Builder config,
      UserContext user,
      boolean expectedValue,
      Reason expectedReason) {
    return Arguments.of(Named.of(name, config), user, expectedValue, expectedReason);
  }
}
