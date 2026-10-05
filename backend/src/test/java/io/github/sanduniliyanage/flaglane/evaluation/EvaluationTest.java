package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EvaluationTest {

  @ParameterizedTest
  @EnumSource(Reason.class)
  void sharedInstanceCarriesTheValueAndReasonAskedFor(Reason reason) {
    assertThat(Evaluation.of(true, reason)).isEqualTo(new Evaluation(true, reason));
    assertThat(Evaluation.of(false, reason)).isEqualTo(new Evaluation(false, reason));
  }

  @Test
  void sameOutcomeIsTheSameInstance() {
    assertThat(Evaluation.of(true, Reason.ROLLOUT)).isSameAs(Evaluation.of(true, Reason.ROLLOUT));
  }

  @Test
  void anEvaluationAlwaysHasAReason() {
    assertThatNullPointerException().isThrownBy(() -> new Evaluation(true, null));
  }
}
