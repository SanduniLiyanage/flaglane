package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Suite 3 — cross-flag independence (FR-EVL-004).
 *
 * <p>This suite fails if the salt is removed from the hash input as a "simplification": every flag
 * at 30% would then select the same 30% of users.
 */
class CrossFlagIndependenceTest {

  @Test
  void twoFlagsAtThirtyPercentWithDifferentSaltsBothReachAboutNinePercentOfAllKeys() {
    List<String> keys = UserKeyFixture.keys();
    FlagConfig checkout = atThirtyPercent("new-checkout").build();
    FlagConfig darkMode = atThirtyPercent("dark-mode").build();

    long both =
        keys.stream().filter(k -> checkout.isInRollout(k) && darkMode.isInRollout(k)).count();

    // Overlap is the fraction of ALL keys receiving both flags: 0.3 x 0.3 = 9%, give or take half
    // a percentage point. It is not the Jaccard ratio |A n B| / |A u B|, which is about 17.6% for
    // the very same result; the two differ by a factor of two and only one is meant here.
    long nine = 9L * keys.size() / 100;
    long halfPoint = keys.size() / 200;
    assertThat(both).as("keys receiving both flags").isBetween(nine - halfPoint, nine + halfPoint);
  }

  @Test
  void twoFlagsSharingASaltSelectExactlyTheSameCohort() {
    List<String> keys = UserKeyFixture.keys();
    FlagConfig api = atThirtyPercent("checkout-api").rolloutSalt("checkout").build();
    FlagConfig web = atThirtyPercent("checkout-web").rolloutSalt("checkout").build();

    List<String> disagreeing =
        keys.stream().filter(k -> api.isInRollout(k) != web.isInRollout(k)).toList();

    assertThat(disagreeing).as("keys one flag reaches and the other does not").isEmpty();
  }

  @Test
  void theSaltNotTheFlagKeyDecidesTheCohort() {
    List<String> keys = UserKeyFixture.keys();
    FlagConfig ownSalt = atThirtyPercent("checkout-api").build();
    FlagConfig sharedSalt = atThirtyPercent("checkout-api").rolloutSalt("checkout").build();

    long disagreeing =
        keys.stream().filter(k -> ownSalt.isInRollout(k) != sharedSalt.isInRollout(k)).count();

    assertThat(disagreeing).as("keys whose rollout changes with the salt alone").isPositive();
  }

  private static FlagConfig.Builder atThirtyPercent(String flagKey) {
    return FlagConfig.builder(flagKey).enabled(true).rolloutBasisPoints(3000);
  }
}
