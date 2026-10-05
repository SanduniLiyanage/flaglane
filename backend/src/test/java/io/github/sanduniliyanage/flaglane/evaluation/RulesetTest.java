package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class RulesetTest {

  @Test
  void findsAConfigurationByFlagKey() {
    FlagConfig checkout = FlagConfig.builder("new-checkout").build();
    Ruleset ruleset = Ruleset.of("production", 412, List.of(checkout));

    assertThat(ruleset.flag("new-checkout")).containsSame(checkout);
    assertThat(ruleset.environment()).isEqualTo("production");
    assertThat(ruleset.version()).isEqualTo(412);
    assertThat(ruleset.flags()).containsExactly(checkout);
  }

  @Test
  void unknownAndNullFlagKeysAreNotFoundRatherThanAnError() {
    Ruleset ruleset = Ruleset.of("production", 1, List.of(FlagConfig.builder("a").build()));

    assertThat(ruleset.flag("spelled-wrong")).isEmpty();
    assertThat(ruleset.flag(null)).isEmpty();
  }

  @Test
  void twoConfigurationsForOneFlagKeyAreRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                Ruleset.of(
                    "production",
                    1,
                    List.of(FlagConfig.builder("a").build(), FlagConfig.builder("a").build())));
  }

  @Test
  void emptyRulesetServesNothing() {
    assertThat(Ruleset.empty("staging", 0).flags()).isEmpty();
  }
}
