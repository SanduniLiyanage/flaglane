package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValueTest {

  @Test
  void numberOneNeverEqualsStringOne() {
    assertThat(Value.of(1)).isNotEqualTo(Value.of("1"));
  }

  @Test
  void booleanTrueNeverEqualsStringTrue() {
    assertThat(Value.of(true)).isNotEqualTo(Value.of("true"));
  }

  @Test
  void stringsDifferingOnlyInCapitalisationAreDifferentValues() {
    assertThat(Value.of("LK")).isNotEqualTo(Value.of("lk"));
  }

  @Test
  void stringsDifferingOnlyInUnicodeNormalisationAreDifferentValues() {
    assertThat(Value.of("café")).isNotEqualTo(Value.of("café"));
  }

  @Test
  void integerAndDecimalSpellingsOfOneNumberAreEqualAsInJavaScript() {
    assertThat(Value.from(1)).isEqualTo(Value.from(1.0)).contains(Value.of(1));
    assertThat(Value.from(1L)).isEqualTo(Value.from(new BigDecimal("1.00")));
  }

  @Test
  void negativeZeroEqualsZeroAsInJavaScript() {
    assertThat(Value.of(-0.0)).isEqualTo(Value.of(0.0)).hasSameHashCodeAs(Value.of(0.0));
  }

  @Test
  void integersBeyondDoublePrecisionCollapseAsTheyDoInJavaScript() {
    // 2^53 + 1 is not representable as a double; JSON.parse yields 2^53 for it.
    Object parsed = new BigInteger("9007199254740993");

    assertThat(Value.from(parsed)).contains(Value.of(9007199254740992.0));
  }

  @Test
  void nonFiniteNumbersAreNotValues() {
    assertThat(Value.from(Double.NaN)).isEmpty();
    assertThat(Value.from(Double.POSITIVE_INFINITY)).isEmpty();
    assertThatIllegalArgumentException().isThrownBy(() -> Value.of(Double.NaN));
  }

  @Test
  void nullArraysAndObjectsAreNotValues() {
    assertThat(Value.from(null)).isEmpty();
    assertThat(Value.from(List.of("US"))).isEmpty();
    assertThat(Value.from(Map.of("country", "US"))).isEmpty();
    assertThat(Value.from(new Object())).isEmpty();
  }

  @Test
  void stringsNumbersAndBooleansConvertToTheirOwnType() {
    assertThat(Value.from("pro")).contains(new StringValue("pro"));
    assertThat(Value.from(42)).contains(new NumberValue(42));
    assertThat(Value.from(false)).contains(new BooleanValue(false));
  }
}
