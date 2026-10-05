package io.github.sanduniliyanage.flaglane.evaluation;

import java.util.Objects;

/**
 * A string attribute or operand. Compared case-sensitively with no Unicode normalisation and no
 * locale (FR-RUL-007).
 */
public record StringValue(String value) implements Value {

  public StringValue {
    Objects.requireNonNull(value, "value");
  }
}
