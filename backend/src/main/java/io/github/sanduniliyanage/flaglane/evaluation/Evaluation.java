package io.github.sanduniliyanage.flaglane.evaluation;

import java.util.Objects;

/**
 * The outcome of evaluating one flag: the value, and the step of the resolution order that produced
 * it.
 */
public record Evaluation(boolean value, Reason reason) {

  private static final Evaluation[] CACHE = new Evaluation[Reason.values().length * 2];

  static {
    for (Reason reason : Reason.values()) {
      CACHE[index(false, reason)] = new Evaluation(false, reason);
      CACHE[index(true, reason)] = new Evaluation(true, reason);
    }
  }

  public Evaluation {
    Objects.requireNonNull(reason, "reason");
  }

  /**
   * The shared instance for this outcome; there are only fourteen, so the hot path allocates none.
   */
  public static Evaluation of(boolean value, Reason reason) {
    return CACHE[index(value, reason)];
  }

  private static int index(boolean value, Reason reason) {
    return reason.ordinal() * 2 + (value ? 1 : 0);
  }
}
