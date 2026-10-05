package io.github.sanduniliyanage.flaglane.evaluation;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The seven targeting operators (FR-RUL-003). There is no regular-expression operator: neither
 * runtime can bound a backtracking match, and a pattern that never finishes freezes the caller's
 * request thread or browser tab (E-009).
 */
public enum Operator {
  EQUALS(Shape.SINGLE),
  NOT_EQUALS(Shape.SINGLE),
  IN(Shape.LIST),
  NOT_IN(Shape.LIST),
  CONTAINS(Shape.SINGLE),
  STARTS_WITH(Shape.SINGLE),
  ENDS_WITH(Shape.SINGLE);

  /** How many match values an operator takes (ADR-019). */
  enum Shape {
    /** Exactly one. */
    SINGLE,
    /** One or more, all of one type. */
    LIST
  }

  private static final Map<String, Operator> BY_NAME =
      Arrays.stream(values())
          .collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

  private final Shape shape;

  Operator(Shape shape) {
    this.shape = shape;
  }

  Shape shape() {
    return shape;
  }

  /**
   * The operator with exactly this name, or empty. Case-sensitive, as stored and as checked by the
   * database: {@code "equals"} is not an operator.
   */
  public static Optional<Operator> named(String name) {
    return name == null ? Optional.empty() : Optional.ofNullable(BY_NAME.get(name));
  }
}
