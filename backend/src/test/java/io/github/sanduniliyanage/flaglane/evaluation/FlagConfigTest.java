package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlagConfigTest {

  @Test
  void newConfigurationHasTheDefaultsOfANewlyCreatedFlag() {
    FlagConfig config = FlagConfig.builder("new-checkout").build();

    assertThat(config.enabled()).isFalse();
    assertThat(config.fallthroughValue()).isFalse();
    assertThat(config.rolloutBasisPoints()).isZero();
    assertThat(config.rules()).isEmpty();
    assertThat(config.overrides()).isEmpty();
  }

  @Test
  void rolloutSaltDefaultsToTheFlagKey() {
    assertThat(FlagConfig.builder("new-checkout").build().rolloutSalt()).isEqualTo("new-checkout");
  }

  @Test
  void offValueIsFalseWhateverElseIsConfigured() {
    FlagConfig config =
        FlagConfig.builder("new-checkout")
            .enabled(false)
            .fallthroughValue(true)
            .rolloutBasisPoints(10_000)
            .override("u-1", true)
            .build();

    assertThat(config.offValue()).isFalse();
  }

  @Test
  void rolloutOutsideZeroToTenThousandBasisPointsIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> FlagConfig.builder("f").rolloutBasisPoints(-1).build());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> FlagConfig.builder("f").rolloutBasisPoints(10_001).build());
    assertThat(FlagConfig.builder("f").rolloutBasisPoints(10_000).build().rolloutBasisPoints())
        .isEqualTo(10_000);
  }

  @Test
  void rulesAreHeldInAscendingPriorityKeepingArrivalOrderForTies() {
    TargetingRule second = rule(1, "b");
    TargetingRule firstOfTie = rule(0, "a1");
    TargetingRule secondOfTie = rule(0, "a2");

    FlagConfig config =
        FlagConfig.builder("f").rule(second).rule(firstOfTie).rule(secondOfTie).build();

    assertThat(config.rules()).containsExactly(firstOfTie, secondOfTie, second);
  }

  @Test
  void rulesAndOverridesCannotBeChangedAfterConstruction() {
    FlagConfig config = FlagConfig.builder("f").rule(rule(0, "a")).override("u-1", true).build();

    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> config.rules().add(rule(1, "b")));
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> config.overrides().put("u-2", true));
  }

  @Test
  void ruleMatchValuesAreCopiedAndMayCarryNulls() {
    List<Object> values = new ArrayList<>(Arrays.asList("LK", null));

    TargetingRule rule = new TargetingRule(0, "country", "IN", values, true);
    values.clear();

    assertThat(rule.matchValues()).isEqualTo(Arrays.asList("LK", null));
    assertThat(new TargetingRule(0, "country", "IN", null, true).matchValues()).isNull();
  }

  @Test
  void toBuilderReproducesTheConfiguration() {
    FlagConfig original =
        FlagConfig.builder("checkout-web")
            .enabled(true)
            .fallthroughValue(true)
            .rolloutBasisPoints(2_500)
            .rolloutSalt("checkout")
            .override("u-1", false)
            .rule(rule(0, "a"))
            .build();

    FlagConfig copy = original.toBuilder().build();

    assertThat(copy).usingRecursiveComparison().isEqualTo(original);
  }

  private static TargetingRule rule(int priority, String value) {
    return new TargetingRule(priority, "attr", "EQUALS", List.of(value), true);
  }
}
