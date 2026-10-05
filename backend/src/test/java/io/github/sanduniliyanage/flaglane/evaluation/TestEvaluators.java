package io.github.sanduniliyanage.flaglane.evaluation;

import java.time.Clock;

/** Engines for tests that assert on results, not on what the engine logs. */
final class TestEvaluators {

  private TestEvaluators() {}

  /** An engine that discards its warnings, so suites with malformed rows keep the log readable. */
  static Evaluator quiet() {
    return new Evaluator(Clock.systemUTC(), (flagKey, message, cause) -> {});
  }
}
