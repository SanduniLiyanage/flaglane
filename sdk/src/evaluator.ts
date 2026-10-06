import { isInRollout } from "./bucketing.js";
import type { Ruleset } from "./ruleset.js";
import type { Evaluation, Logger, Reason, User } from "./types.js";
import { Warnings } from "./warnings.js";

const consoleLogger: Logger = {
  warn: (message, error) => console.warn(message, ...(error === undefined ? [] : [error])),
};

/**
 * The evaluation engine, the same resolution order as the server's (FR-EVL-001, FR-SDK-007):
 *
 * 1. Kill switch: disabled returns false. Nothing else is consulted.
 * 2. User override: an exact match on the user key returns its value.
 * 3. Targeting rules: priority ascending, the first match returns its result value.
 * 4. Percentage rollout: `bucket < rolloutBasisPoints` returns true.
 * 5. Fallthrough: the fallthrough value.
 *
 * Without a user key, steps 2 and 4 are skipped and rules still apply (FR-EVL-005). It never
 * throws: a malformed rule that evaluation reaches, or any internal error, resolves a known flag to
 * its fallthrough value; no ruleset or an unknown flag resolves to the caller's fallback
 * (FR-EVL-006, FR-EVL-007, FR-SDK-006). Each of those logs a warning, at most once per flag key per
 * minute.
 */
export class Evaluator {
  private readonly warnings: Warnings;

  constructor(options: { logger?: Logger; clock?: () => number } = {}) {
    this.warnings = new Warnings(options.logger ?? consoleLogger, options.clock ?? Date.now);
  }

  evaluate(
    ruleset: Ruleset | undefined,
    flagKey: unknown,
    user: User | undefined,
    fallback: boolean,
  ): Evaluation {
    const safeFallback = fallback === true;
    let fallthrough: boolean | undefined;
    try {
      if (ruleset === undefined) {
        this.warnings.warn(flagKey, "was evaluated with no ruleset loaded; returned the fallback");
        return result(safeFallback, "ERROR");
      }
      const flag = typeof flagKey === "string" ? ruleset.flags.get(flagKey) : undefined;
      if (flag === undefined) {
        this.warnings.warn(flagKey, "is not in the ruleset; returned the fallback");
        return result(safeFallback, "FLAG_NOT_FOUND");
      }
      if (flag.broken) {
        this.warnings.warn(flagKey, `could not be read (${flag.problem}); returned the fallback`);
        return result(safeFallback, "ERROR");
      }
      fallthrough = flag.fallthroughValue;
      if (!flag.enabled) {
        return result(false, "OFF");
      }
      const key = user?.key;
      if (key !== undefined) {
        const override = flag.overrides.get(key);
        if (override !== undefined) {
          return result(override, "OVERRIDE");
        }
      }
      for (const rule of flag.rules) {
        if (rule.malformation !== undefined) {
          this.warnings.warn(
            flagKey,
            `has a malformed rule (${rule.malformation}); returned the fallthrough value`,
          );
          return result(flag.fallthroughValue, "ERROR");
        }
        if (user !== undefined && rule.matches(user)) {
          return result(rule.resultValue, "RULE_MATCH");
        }
      }
      if (key !== undefined && isInRollout(flag.rolloutSalt, key, flag.rolloutBasisPoints)) {
        return result(true, "ROLLOUT");
      }
      return result(flag.fallthroughValue, "FALLTHROUGH");
    } catch (error) {
      this.warnings.warn(flagKey, "could not be evaluated; returned a safe value", error);
      return result(fallthrough ?? safeFallback, "ERROR");
    }
  }
}

function result(value: boolean, reason: Reason): Evaluation {
  return { value, reason };
}
