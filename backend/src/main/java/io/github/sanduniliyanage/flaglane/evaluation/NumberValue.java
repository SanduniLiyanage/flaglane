package io.github.sanduniliyanage.flaglane.evaluation;

import java.util.Optional;

/**
 * A numeric attribute or operand, held as an IEEE-754 double so that it compares exactly as the
 * TypeScript SDK's {@code ===} does (ADR-017).
 */
public record NumberValue(double value) implements Value {

  public NumberValue {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("A number value must be finite, was " + value);
    }
    // JavaScript's === treats -0 and 0 as equal; a record compares doubles bit for bit. Folding
    // negative zero here keeps equals() in line with the SDK.
    if (value == 0.0) {
      value = 0.0;
    }
  }

  static Optional<Value> from(Number number) {
    double value = number.doubleValue();
    return Double.isFinite(value) ? Optional.of(new NumberValue(value)) : Optional.empty();
  }
}
