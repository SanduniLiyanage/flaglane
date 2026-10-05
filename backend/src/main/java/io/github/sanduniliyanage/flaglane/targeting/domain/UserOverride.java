package io.github.sanduniliyanage.flaglane.targeting.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A user override (FR-RUL-001): this user key gets this value. Applies to server keys only; a
 * client key's ruleset carries no overrides at all (FR-KEY-008).
 */
public record UserOverride(String userKey, boolean value) {

  /** As the audit trail records it. */
  public Map<String, Object> describe() {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("userKey", userKey);
    state.put("value", value);
    return state;
  }
}
