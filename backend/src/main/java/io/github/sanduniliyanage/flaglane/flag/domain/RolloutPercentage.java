package io.github.sanduniliyanage.flaglane.flag.domain;

/**
 * The two units of a rollout (ADR-011). The management API and the dashboard speak whole
 * percentages, 0 to 100; the database and the ruleset speak basis points, 0 to 10000. Both are
 * integers on both sides of the conversion: there is no float anywhere in the path.
 */
public final class RolloutPercentage {

  public static final int MAX = 100;
  private static final int BASIS_POINTS_PER_PERCENT = 100;

  private RolloutPercentage() {}

  public static int toBasisPoints(int percentage) {
    if (percentage < 0 || percentage > MAX) {
      throw new IllegalArgumentException("A rollout percentage is 0 to 100, was " + percentage);
    }
    return percentage * BASIS_POINTS_PER_PERCENT;
  }

  /**
   * Whole percent, rounded down. Exact for every rollout the management API can set; a sub-percent
   * rollout, once something can set one, shows as the whole percent below it.
   */
  public static int fromBasisPoints(int basisPoints) {
    return basisPoints / BASIS_POINTS_PER_PERCENT;
  }
}
