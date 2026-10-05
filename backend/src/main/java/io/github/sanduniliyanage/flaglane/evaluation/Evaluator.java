package io.github.sanduniliyanage.flaglane.evaluation;

import java.time.Clock;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * The evaluation engine: {@code evaluate(Ruleset, flagKey, UserContext, fallback)}.
 *
 * <p>Resolution order, first match wins (FR-EVL-001):
 *
 * <ol>
 *   <li><b>Kill switch.</b> Disabled → the off value, {@code false}. Nothing else is consulted.
 *   <li><b>User override.</b> An exact match on the user key → its value.
 *   <li><b>Targeting rules.</b> Priority ascending, first match → its result value.
 *   <li><b>Percentage rollout.</b> {@code bucket < rolloutBasisPoints} → {@code true}.
 *   <li><b>Fallthrough.</b> The fallthrough value.
 * </ol>
 *
 * <p>Without a user key, steps 2 and 4 are skipped and rules still apply (FR-EVL-005).
 *
 * <p><b>Evaluation never throws.</b> Callers sit in request paths that must not fail because a flag
 * could not be read. A malformed rule that evaluation reaches, or any internal error, resolves a
 * known flag to its fallthrough value (FR-EVL-006, ADR-020); a flag that cannot be found, or a
 * ruleset that is not there, resolves to the caller's fallback (FR-EVL-007). Each logs a warning,
 * at most once per flag key per minute.
 *
 * <p>Thread-safe. One instance serves every request.
 */
public final class Evaluator {

  private final WarningRateLimiter rateLimiter;
  private final WarningSink warnings;
  private final BiFunction<FlagConfig, UserContext, Evaluation> resolver;

  /** An engine logging through the JDK's platform logger, rate-limited by {@code clock}. */
  public Evaluator(Clock clock) {
    this(clock, WarningSink.platformLogger());
  }

  Evaluator(Clock clock, WarningSink warnings) {
    this(clock, warnings, null);
  }

  /**
   * Visible for testing: {@code resolver} replaces the resolution step, so that a test can make the
   * engine fail inside; {@code null} keeps the real one.
   */
  Evaluator(
      Clock clock, WarningSink warnings, BiFunction<FlagConfig, UserContext, Evaluation> resolver) {
    this.rateLimiter = new WarningRateLimiter(Objects.requireNonNull(clock, "clock"));
    this.warnings = Objects.requireNonNull(warnings, "warnings");
    this.resolver = resolver == null ? this::resolve : resolver;
  }

  /**
   * Evaluates one flag for one user. Never throws.
   *
   * @param ruleset the environment's current ruleset; {@code null} resolves to the fallback
   * @param flagKey the flag to evaluate
   * @param user who it is for; {@code null} evaluates anonymously
   * @param fallback what the caller has decided is safe; returned when no configuration for the
   *     flag is reachable (FR-EVL-007)
   */
  public Evaluation evaluate(Ruleset ruleset, String flagKey, UserContext user, boolean fallback) {
    FlagConfig config = null;
    try {
      if (ruleset == null) {
        warn(flagKey, "was evaluated with no ruleset loaded; returned the caller's fallback", null);
        return Evaluation.of(fallback, Reason.ERROR);
      }
      config = ruleset.flag(flagKey).orElse(null);
      if (config == null) {
        warn(flagKey, "is not in the ruleset; returned the caller's fallback", null);
        return Evaluation.of(fallback, Reason.FLAG_NOT_FOUND);
      }
      return resolver.apply(config, user == null ? UserContext.anonymous() : user);
    } catch (RuntimeException e) {
      if (config == null) {
        warn(flagKey, "could not be evaluated; returned the caller's fallback", e);
        return Evaluation.of(fallback, Reason.ERROR);
      }
      warn(flagKey, "could not be evaluated; returned the fallthrough value", e);
      return Evaluation.of(config.fallthroughValue(), Reason.ERROR);
    }
  }

  private Evaluation resolve(FlagConfig config, UserContext user) {
    if (!config.enabled()) {
      return Evaluation.of(config.offValue(), Reason.OFF);
    }
    if (user.hasKey()) {
      Boolean override = config.overrides().get(user.key());
      if (override != null) {
        return Evaluation.of(override, Reason.OVERRIDE);
      }
    }
    for (CompiledRule rule : config.compiledRules()) {
      if (rule.isMalformed()) {
        warn(
            config.key(),
            "has a malformed rule (" + rule.malformation() + "); returned the fallthrough value",
            null);
        return Evaluation.of(config.fallthroughValue(), Reason.ERROR);
      }
      if (rule.matches(user)) {
        return Evaluation.of(rule.resultValue(), Reason.RULE_MATCH);
      }
    }
    if (user.hasKey() && config.isInRollout(user.key())) {
      return Evaluation.of(true, Reason.ROLLOUT);
    }
    return Evaluation.of(config.fallthroughValue(), Reason.FALLTHROUGH);
  }

  /**
   * Rate-limited, and itself unable to throw: a logger that fails must not turn a handled error
   * into an unhandled one. The flag key may have come from a caller and may name no flag at all, so
   * it is truncated and stripped of control characters before it reaches a log line. The user key
   * is never logged.
   */
  private void warn(String flagKey, String message, Throwable cause) {
    try {
      if (rateLimiter.tryAcquire(flagKey)) {
        warnings.warn(printable(flagKey), message, cause);
      }
    } catch (RuntimeException loggingFailure) {
      // Nothing left to tell, and nobody to tell it to without risking the caller's request.
    }
  }

  static String printable(String flagKey) {
    if (flagKey == null) {
      return "<null>";
    }
    String shown =
        flagKey.length() > WarningRateLimiter.MAX_KEY_LENGTH
            ? flagKey.substring(0, WarningRateLimiter.MAX_KEY_LENGTH) + "..."
            : flagKey;
    StringBuilder safe = new StringBuilder(shown.length());
    shown.codePoints().forEach(cp -> safe.appendCodePoint(Character.isISOControl(cp) ? '?' : cp));
    return safe.toString();
  }
}
