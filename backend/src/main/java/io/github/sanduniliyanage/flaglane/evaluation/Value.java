package io.github.sanduniliyanage.flaglane.evaluation;

import java.util.Optional;

/**
 * A user attribute, or one operand of a targeting rule: a string, a number or a boolean, and
 * nothing else (FR-RUL-006).
 *
 * <p>Equality is type-strict (FR-RUL-007): the number {@code 1} never equals the string {@code
 * "1"}. Numbers are IEEE-754 doubles, as every number in the TypeScript SDK is, so {@code 1} equals
 * {@code 1.0} and integers beyond 2<sup>53</sup> lose precision identically on both sides
 * (ADR-017).
 */
public sealed interface Value permits StringValue, NumberValue, BooleanValue {

  static Value of(String value) {
    return new StringValue(value);
  }

  static Value of(double value) {
    return new NumberValue(value);
  }

  static Value of(boolean value) {
    return new BooleanValue(value);
  }

  /**
   * Converts a decoded JSON scalar. Anything that is not a string, a finite number or a boolean —
   * null, an array, an object — is not a value, and the engine ignores it (FR-RUL-006).
   */
  static Optional<Value> from(Object raw) {
    return switch (raw) {
      case String text -> Optional.of(new StringValue(text));
      case Boolean bool -> Optional.of(new BooleanValue(bool));
      case Number number -> NumberValue.from(number);
      case null, default -> Optional.empty();
    };
  }
}
