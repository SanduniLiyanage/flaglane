package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Suite 1 — bucketing distribution (FR-EVL-002). Substantiates: a percentage rollout means what it
 * says.
 *
 * <p>The key set is fixed, so the computation is fully deterministic and the band is a safety
 * margin rather than a confidence interval.
 */
class BucketingDistributionTest {

  /** Half a percentage point, as a count of keys: 50 basis points of 100,000. */
  private static final long TOLERANCE = 50L * UserKeyFixture.SIZE / 10_000;

  @ParameterizedTest(name = "{0} basis points")
  @ValueSource(ints = {100, 3000, 5000, 9900})
  void rolloutReachesItsShareOfTheKeySetWithinHalfAPercentagePoint(int basisPoints) {
    List<String> keys = UserKeyFixture.keys();
    FlagConfig config =
        FlagConfig.builder("new-checkout").enabled(true).rolloutBasisPoints(basisPoints).build();

    long reached = keys.stream().filter(config::isInRollout).count();

    long expected = (long) basisPoints * keys.size() / 10_000;
    assertThat(reached)
        .as("keys reached by a %d basis point rollout", basisPoints)
        .isBetween(expected - TOLERANCE, expected + TOLERANCE);
  }
}
