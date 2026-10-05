package io.github.sanduniliyanage.flaglane.evaluation;

/** Why an evaluation produced its value; reported per flag by {@code POST /sdk/evaluate}. */
public enum Reason {
  /** The configuration is disabled: the kill switch. */
  OFF,
  /** A user override for this user key. */
  OVERRIDE,
  /** A targeting rule matched. */
  RULE_MATCH,
  /** The user's bucket is below the rollout. */
  ROLLOUT,
  /** Enabled, and nothing else applied. */
  FALLTHROUGH,
  /** No configuration for the flag key is reachable: the caller's fallback. */
  FLAG_NOT_FOUND,
  /** Evaluation could not complete; the fallthrough value, or the fallback without a flag. */
  ERROR
}
