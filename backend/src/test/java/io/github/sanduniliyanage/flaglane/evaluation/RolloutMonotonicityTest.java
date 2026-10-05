package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Suite 11 — rollout monotonicity (FR-EVL-008). Substantiates: raising a rollout never takes the
 * feature away from someone who already had it, which is what makes the rollout slider safe to
 * move.
 *
 * <p>This suite fails if {@code bucket <} becomes {@code bucket <=}: the 16 keys of the committed
 * set in bucket 0 would then receive a flag at 0%. It also fails if the bucket comes to depend on
 * the rollout itself, because each step would then reshuffle rather than extend the cohort.
 */
class RolloutMonotonicityTest {

  @Test
  void raisingTheRolloutInHundredBasisPointStepsNeverTakesTheFlagAwayFromAnyone() {
    List<String> keys = UserKeyFixture.keys();
    FlagConfig flag = FlagConfig.builder("new-checkout").enabled(true).build();

    BitSet previous = reached(flag, keys);
    assertThat(previous.cardinality()).as("keys reached at 0 basis points").isZero();
    for (int basisPoints = 100; basisPoints <= 10_000; basisPoints += 100) {
      BitSet current = reached(flag.toBuilder().rolloutBasisPoints(basisPoints).build(), keys);

      BitSet lost = (BitSet) previous.clone();
      lost.andNot(current);
      assertThat(lost.cardinality())
          .as("keys reached at %d basis points and lost at %d", basisPoints - 100, basisPoints)
          .isZero();
      previous = current;
    }
    assertThat(previous.cardinality())
        .as("keys reached at 10000 basis points")
        .isEqualTo(keys.size());
  }

  private static BitSet reached(FlagConfig flag, List<String> keys) {
    BitSet reached = new BitSet(keys.size());
    for (int i = 0; i < keys.size(); i++) {
      reached.set(i, flag.isInRollout(keys.get(i)));
    }
    return reached;
  }
}
