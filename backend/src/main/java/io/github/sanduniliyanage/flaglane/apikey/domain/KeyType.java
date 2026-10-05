package io.github.sanduniliyanage.flaglane.apikey.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/**
 * What a key may read (FR-KEY-001): a {@code server} key every flag in its environment, a {@code
 * client} key only client-side-visible flags and no overrides (FR-KEY-004, FR-KEY-005, FR-KEY-008).
 */
public enum KeyType {
  SERVER("server", "flg_srv_"),
  CLIENT("client", "flg_cli_");

  private final String value;
  private final String marker;

  KeyType(String value, String marker) {
    this.value = value;
    this.marker = marker;
  }

  /** As stored in {@code api_keys.key_type} and written in the API. */
  @JsonValue
  public String value() {
    return value;
  }

  /**
   * The prefix a key of this type starts with. For humans and secret scanners only: a key's type is
   * always read from its database row, never from this marker (docs/DATABASE.md).
   */
  public String marker() {
    return marker;
  }

  public static Optional<KeyType> fromValue(String value) {
    return Arrays.stream(values()).filter(type -> type.value.equals(value)).findFirst();
  }
}
