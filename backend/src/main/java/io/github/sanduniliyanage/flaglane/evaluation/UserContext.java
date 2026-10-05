package io.github.sanduniliyanage.flaglane.evaluation;

import java.util.HashMap;
import java.util.Map;

/**
 * Who a flag is being evaluated for: an optional user key and a set of typed attributes.
 *
 * <p>The key drives user overrides and the percentage rollout. Without one, both are skipped and
 * targeting rules still apply to whatever attributes were supplied (FR-EVL-005). An empty key is
 * treated as no key, because bucketing every caller that sent {@code ""} would put all of them in
 * one bucket (ADR-017).
 *
 * @param key the user key, or {@code null} for an anonymous evaluation
 * @param attributes attribute values by name; never null
 */
public record UserContext(String key, Map<String, Value> attributes) {

  private static final UserContext ANONYMOUS = new UserContext(null, Map.of());

  public UserContext {
    if (key != null && key.isEmpty()) {
      key = null;
    }
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  public static UserContext anonymous() {
    return ANONYMOUS;
  }

  public static UserContext of(String key) {
    return new UserContext(key, Map.of());
  }

  /**
   * Builds a context from decoded JSON. An attribute that is not a string, a finite number or a
   * boolean is dropped rather than rejected: the management API refuses such attributes, and the
   * engine ignores them (FR-RUL-006), so a rule against one behaves as against an absent attribute.
   */
  public static UserContext fromRaw(String key, Map<String, ?> rawAttributes) {
    Map<String, Value> attributes = new HashMap<>();
    if (rawAttributes != null) {
      rawAttributes.forEach(
          (name, raw) -> {
            if (name != null) {
              Value.from(raw).ifPresent(value -> attributes.put(name, value));
            }
          });
    }
    return new UserContext(key, attributes);
  }

  /** The attribute's value, or {@code null} when the context does not carry it. */
  public Value attribute(String name) {
    return name == null ? null : attributes.get(name);
  }

  public boolean hasKey() {
    return key != null;
  }
}
