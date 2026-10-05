package io.github.sanduniliyanage.flaglane.flag.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RolloutPercentageTest {

  @ParameterizedTest
  @CsvSource({"0,0", "1,100", "30,3000", "99,9900", "100,10000"})
  void aWholePercentIsAHundredBasisPoints(int percentage, int basisPoints) {
    assertThat(RolloutPercentage.toBasisPoints(percentage)).isEqualTo(basisPoints);
    assertThat(RolloutPercentage.fromBasisPoints(basisPoints)).isEqualTo(percentage);
  }

  @Test
  void aSubPercentRolloutShowsAsTheWholePercentBelowIt() {
    assertThat(RolloutPercentage.fromBasisPoints(10)).isZero();
    assertThat(RolloutPercentage.fromBasisPoints(2_550)).isEqualTo(25);
  }

  @Test
  void aPercentageOutsideZeroToAHundredIsRefused() {
    assertThatIllegalArgumentException().isThrownBy(() -> RolloutPercentage.toBasisPoints(-1));
    assertThatIllegalArgumentException().isThrownBy(() -> RolloutPercentage.toBasisPoints(101));
  }
}
