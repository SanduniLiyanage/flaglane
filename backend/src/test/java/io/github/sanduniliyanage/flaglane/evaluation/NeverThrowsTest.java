package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Suite 7 — evaluation never throws (FR-EVL-006, FR-EVL-007). Every input below is one a caller or
 * a bad write could produce. Each call must return — the fallthrough value where the flag's
 * configuration was reached, the caller's fallback where it was not — and none may throw.
 *
 * <p>The fallthrough value is {@code true} and the caller's fallback {@code false} throughout, so
 * which of the two came back is never ambiguous.
 */
class NeverThrowsTest {

  private static final String FLAG = "new-checkout";
  private static final boolean FALLTHROUGH = true;
  private static final boolean FALLBACK = false;

  static Stream<Arguments> inputs() {
    return Stream.of(
        input(
            "a malformed rule: no match values",
            flagWith(new TargetingRule(0, "plan", "IN", List.of(), false)),
            UserContext.of("u-1"),
            FALLTHROUGH,
            Reason.ERROR),
        input(
            "a malformed rule: a null match value",
            flagWith(new TargetingRule(0, "plan", "IN", Arrays.asList("pro", null), false)),
            UserContext.of("u-1"),
            FALLTHROUGH,
            Reason.ERROR),
        input(
            "a malformed rule: no attribute",
            flagWith(new TargetingRule(0, null, "EQUALS", List.of("pro"), false)),
            UserContext.of("u-1"),
            FALLTHROUGH,
            Reason.ERROR),
        input(
            "an unknown operator",
            flagWith(new TargetingRule(0, "plan", "MATCHES_REGEX", List.of("(a+)+$"), false)),
            UserContext.of("u-1"),
            FALLTHROUGH,
            Reason.ERROR),
        input(
            "a null operator",
            flagWith(new TargetingRule(0, "plan", null, List.of("pro"), false)),
            UserContext.of("u-1"),
            FALLTHROUGH,
            Reason.ERROR),
        input(
            "a null user key",
            () ->
                ruleset(
                    FlagConfig.builder(FLAG)
                        .enabled(true)
                        .fallthroughValue(FALLTHROUGH)
                        .override("u-1", false)
                        .rolloutBasisPoints(5_000)
                        .rule(new TargetingRule(0, "plan", "EQUALS", List.of("pro"), false))),
            UserContext.of(null),
            FALLTHROUGH,
            Reason.FALLTHROUGH),
        input(
            "an unknown attribute",
            flagWith(new TargetingRule(0, "no-such-attribute", "NOT_EQUALS", List.of("x"), false)),
            UserContext.fromRaw("u-1", Map.of("plan", "pro")),
            FALLTHROUGH,
            Reason.FALLTHROUGH),
        input(
            "an attribute of an unsupported type",
            flagWith(new TargetingRule(0, "plan", "NOT_IN", List.of("free"), false)),
            UserContext.fromRaw("u-1", Map.of("plan", List.of("pro", "team"))),
            FALLTHROUGH,
            Reason.FALLTHROUGH),
        input(
            "a null user context",
            flagWith(new TargetingRule(0, "plan", "EQUALS", List.of("pro"), false)),
            null,
            FALLTHROUGH,
            Reason.FALLTHROUGH),
        input("a null ruleset", () -> null, UserContext.of("u-1"), FALLBACK, Reason.ERROR),
        input(
            "an empty ruleset",
            () -> Ruleset.empty("production", 1),
            UserContext.of("u-1"),
            FALLBACK,
            Reason.FLAG_NOT_FOUND));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("inputs")
  void returnsRatherThanThrows(
      Supplier<Ruleset> ruleset, UserContext user, boolean expectedValue, Reason expectedReason) {
    Evaluator evaluator = TestEvaluators.quiet();

    Evaluation evaluation = evaluator.evaluate(ruleset.get(), FLAG, user, FALLBACK);

    assertThat(evaluation).isEqualTo(new Evaluation(expectedValue, expectedReason));
  }

  @Test
  void aNullFlagKeyReturnsTheCallersFallback() {
    Ruleset ruleset = flagWith(new TargetingRule(0, "plan", "EQUALS", List.of("pro"), false)).get();

    Evaluation evaluation = TestEvaluators.quiet().evaluate(ruleset, null, null, FALLBACK);

    assertThat(evaluation).isEqualTo(new Evaluation(FALLBACK, Reason.FLAG_NOT_FOUND));
  }

  @Test
  void anInternalFailureResolvesToTheFallthroughValueAndIsLogged() {
    List<String> warnings = new ArrayList<>();
    AtomicReference<Throwable> logged = new AtomicReference<>();
    Evaluator evaluator =
        new Evaluator(
            Clock.systemUTC(),
            (flagKey, message, cause) -> {
              warnings.add(flagKey + " " + message);
              logged.set(cause);
            },
            (config, user) -> {
              throw new IllegalStateException("simulated defect");
            });

    Evaluation evaluation =
        evaluator.evaluate(ruleset(enabled()), FLAG, UserContext.of("u-1"), FALLBACK);

    assertThat(evaluation).isEqualTo(new Evaluation(FALLTHROUGH, Reason.ERROR));
    assertThat(warnings)
        .containsExactly(FLAG + " could not be evaluated; returned the fallthrough value");
    assertThat(logged.get()).hasMessage("simulated defect");
  }

  @Test
  void aLoggerThatThrowsDoesNotMakeEvaluationThrow() {
    Evaluator evaluator =
        new Evaluator(
            Clock.systemUTC(),
            (flagKey, message, cause) -> {
              throw new IllegalStateException("log appender is down");
            });
    Ruleset ruleset = flagWith(new TargetingRule(0, "plan", "IN", List.of(), false)).get();

    Evaluation evaluation = evaluator.evaluate(ruleset, FLAG, UserContext.of("u-1"), FALLBACK);

    assertThat(evaluation).isEqualTo(new Evaluation(FALLTHROUGH, Reason.ERROR));
  }

  @Test
  void anInternalFailureThatALoggerAlsoFailsOnStillReturns() {
    Evaluator evaluator =
        new Evaluator(
            Clock.systemUTC(),
            (flagKey, message, cause) -> {
              throw new IllegalStateException("log appender is down");
            },
            (config, user) -> {
              throw new IllegalStateException("simulated defect");
            });

    Evaluation evaluation =
        evaluator.evaluate(ruleset(enabled()), FLAG, UserContext.of("u-1"), FALLBACK);

    assertThat(evaluation).isEqualTo(new Evaluation(FALLTHROUGH, Reason.ERROR));
  }

  private static FlagConfig.Builder enabled() {
    return FlagConfig.builder(FLAG).enabled(true).fallthroughValue(FALLTHROUGH);
  }

  private static Supplier<Ruleset> flagWith(TargetingRule rule) {
    return () -> ruleset(enabled().rule(rule));
  }

  private static Ruleset ruleset(FlagConfig.Builder flag) {
    return Ruleset.of("production", 1, List.of(flag.build()));
  }

  private static Arguments input(
      String name,
      Supplier<Ruleset> ruleset,
      UserContext user,
      boolean expectedValue,
      Reason expectedReason) {
    return Arguments.of(Named.of(name, ruleset), user, expectedValue, expectedReason);
  }
}
