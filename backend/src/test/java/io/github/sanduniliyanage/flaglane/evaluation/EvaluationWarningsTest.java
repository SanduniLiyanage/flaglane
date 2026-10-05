package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * FR-EVL-006: a warning per problem, at most one per flag key per minute per process, so that a
 * broken flag on a busy path is not an unbounded log write.
 */
class EvaluationWarningsTest {

  /**
   * Held strongly: java.util.logging keeps loggers weakly, and a handler on a collected one is
   * lost.
   */
  private static final Logger EVALUATOR_LOGGER = Logger.getLogger(Evaluator.class.getName());

  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-05T00:00:00Z"));
  private final List<String> warnings = new ArrayList<>();
  private final Evaluator evaluator =
      new Evaluator(clock, (flagKey, message, cause) -> warnings.add(flagKey + " " + message));

  private final Ruleset ruleset =
      Ruleset.of(
          "production",
          1,
          List.of(
              FlagConfig.builder("broken")
                  .enabled(true)
                  .rule(new TargetingRule(0, "plan", "IN", List.of(), true))
                  .build(),
              FlagConfig.builder("also-broken")
                  .enabled(true)
                  .rule(new TargetingRule(0, "plan", "EQUALS", List.of("a", "b"), true))
                  .build()));

  @Test
  void aMalformedRuleIsReportedWithWhatIsWrongWithIt() {
    evaluator.evaluate(ruleset, "broken", UserContext.of("u-1"), false);

    assertThat(warnings)
        .containsExactly(
            "broken has a malformed rule (rule has no match values);"
                + " returned the fallthrough value");
  }

  @Test
  void anUnknownFlagIsReported() {
    evaluator.evaluate(ruleset, "spelled-wrong", UserContext.of("u-1"), true);

    assertThat(warnings)
        .containsExactly("spelled-wrong is not in the ruleset; returned the caller's fallback");
  }

  @Test
  void aMissingRulesetIsReported() {
    evaluator.evaluate(null, "broken", UserContext.of("u-1"), true);

    assertThat(warnings)
        .containsExactly(
            "broken was evaluated with no ruleset loaded; returned the caller's fallback");
  }

  @Test
  void aFlagWarnsAtMostOncePerMinute() {
    for (int i = 0; i < 1_000; i++) {
      evaluator.evaluate(ruleset, "broken", UserContext.of("u-" + i), false);
    }
    clock.advance(Duration.ofSeconds(59));
    evaluator.evaluate(ruleset, "broken", UserContext.of("u-1"), false);

    assertThat(warnings).as("warnings within the first minute").hasSize(1);
  }

  @Test
  void aFlagWarnsAgainOnceAMinuteHasPassed() {
    evaluator.evaluate(ruleset, "broken", UserContext.of("u-1"), false);
    clock.advance(Duration.ofMinutes(1));

    evaluator.evaluate(ruleset, "broken", UserContext.of("u-1"), false);

    assertThat(warnings).hasSize(2);
  }

  @Test
  void eachFlagKeyHasItsOwnAllowance() {
    evaluator.evaluate(ruleset, "broken", UserContext.of("u-1"), false);
    evaluator.evaluate(ruleset, "also-broken", UserContext.of("u-1"), false);
    evaluator.evaluate(ruleset, "broken", UserContext.of("u-2"), false);

    assertThat(warnings).hasSize(2);
  }

  @Test
  void aClockThatMovesBackwardsDoesNotSilenceAFlag() {
    evaluator.evaluate(ruleset, "broken", UserContext.of("u-1"), false);
    clock.advance(Duration.ofHours(-1));

    evaluator.evaluate(ruleset, "broken", UserContext.of("u-1"), false);

    assertThat(warnings).hasSize(2);
  }

  @Test
  void theUserKeyIsNeverLogged() {
    evaluator.evaluate(ruleset, "broken", UserContext.of("amara@example.com"), false);
    evaluator.evaluate(ruleset, "spelled-wrong", UserContext.of("amara@example.com"), false);

    assertThat(warnings).hasSize(2).noneMatch(line -> line.contains("amara"));
  }

  @Test
  void aCallerSuppliedFlagKeyIsMadeSafeToPrint() {
    evaluator.evaluate(ruleset, "x\r\nFAKE LOG LINE", UserContext.of("u-1"), false);
    evaluator.evaluate(ruleset, "k".repeat(10_000), UserContext.of("u-1"), false);

    assertThat(warnings.get(0)).startsWith("x??FAKE LOG LINE ");
    assertThat(warnings.get(1)).startsWith("k".repeat(64) + "... ");
  }

  @Test
  void floodsOfDistinctUnknownKeysAreBoundedInMemory() {
    WarningRateLimiter limiter = new WarningRateLimiter(clock);
    int flood = WarningRateLimiter.MAX_TRACKED_KEYS + 5_000;

    long acquired = 0;
    for (int i = 0; i < flood; i++) {
      acquired += limiter.tryAcquire("junk-" + i) ? 1 : 0;
    }

    assertThat(limiter.trackedKeys()).isEqualTo(WarningRateLimiter.MAX_TRACKED_KEYS);
    assertThat(acquired).isEqualTo(WarningRateLimiter.MAX_TRACKED_KEYS);
  }

  @Test
  void trackedKeysExpireAndMakeRoomAgainAfterAMinute() {
    WarningRateLimiter limiter = new WarningRateLimiter(clock);
    for (int i = 0; i < WarningRateLimiter.MAX_TRACKED_KEYS; i++) {
      limiter.tryAcquire("junk-" + i);
    }
    clock.advance(Duration.ofMinutes(1));

    boolean acquired = limiter.tryAcquire("broken");

    assertThat(acquired).isTrue();
    assertThat(limiter.trackedKeys()).isEqualTo(1);
  }

  @Test
  void aKnownKeyStillWarnsWhileTheTrackerIsFull() {
    WarningRateLimiter limiter = new WarningRateLimiter(clock);
    limiter.tryAcquire("broken");
    for (int i = 0; i < WarningRateLimiter.MAX_TRACKED_KEYS; i++) {
      limiter.tryAcquire("junk-" + i);
    }
    clock.advance(Duration.ofSeconds(30));
    limiter.tryAcquire("more-junk");
    clock.advance(Duration.ofSeconds(30));

    assertThat(limiter.tryAcquire("broken")).isTrue();
  }

  @Test
  void theDefaultEngineWarnsThroughThePlatformLogger() {
    List<LogRecord> records = new ArrayList<>();
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord logRecord) {
            records.add(logRecord);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    EVALUATOR_LOGGER.addHandler(capture);
    try {
      new Evaluator(clock).evaluate(ruleset, "spelled-wrong", UserContext.of("u-1"), true);
    } finally {
      EVALUATOR_LOGGER.removeHandler(capture);
    }

    assertThat(records)
        .singleElement()
        .satisfies(logRecord -> assertThat(logRecord.getLevel()).isEqualTo(Level.WARNING))
        .satisfies(
            logRecord ->
                assertThat(logRecord.getMessage())
                    .isEqualTo(
                        "Flag 'spelled-wrong' is not in the ruleset;"
                            + " returned the caller's fallback"));
  }

  @Test
  void keysLongerThanAnyFlagKeyAreTrackedByTheirPrefix() {
    WarningRateLimiter limiter = new WarningRateLimiter(clock);

    assertThat(limiter.tryAcquire("k".repeat(64) + "first")).isTrue();
    assertThat(limiter.tryAcquire("k".repeat(64) + "second")).isFalse();
  }
}
