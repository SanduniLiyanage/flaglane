package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UserContextTest {

  @Test
  void emptyUserKeyIsTreatedAsNoUserKey() {
    UserContext context = UserContext.of("");

    assertThat(context.hasKey()).isFalse();
    assertThat(context.key()).isNull();
  }

  @Test
  void whitespaceUserKeyIsStillAUserKey() {
    assertThat(UserContext.of(" ").hasKey()).isTrue();
  }

  @Test
  void rawAttributesOfUnsupportedTypesAreDropped() {
    Map<String, Object> raw = new HashMap<>();
    raw.put("country", "LK");
    raw.put("seats", 12);
    raw.put("beta", true);
    raw.put("tags", List.of("a", "b"));
    raw.put("address", Map.of("city", "Colombo"));
    raw.put("nothing", null);
    raw.put(null, "orphan");

    UserContext context = UserContext.fromRaw("u-1", raw);

    assertThat(context.attributes())
        .containsOnly(
            Map.entry("country", Value.of("LK")),
            Map.entry("seats", Value.of(12)),
            Map.entry("beta", Value.of(true)));
  }

  @Test
  void absentAttributeIsNullAndNullNameIsAbsent() {
    UserContext context = UserContext.fromRaw(null, Map.of("country", "LK"));

    assertThat(context.attribute("plan")).isNull();
    assertThat(context.attribute(null)).isNull();
  }

  @Test
  void attributesCannotBeChangedAfterConstruction() {
    Map<String, Value> attributes = new HashMap<>(Map.of("plan", Value.of("pro")));
    UserContext context = new UserContext("u-1", attributes);

    attributes.put("plan", Value.of("free"));

    assertThat(context.attribute("plan")).isEqualTo(Value.of("pro"));
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> context.attributes().put("plan", Value.of("free")));
  }

  @Test
  void anonymousContextHasNoKeyAndNoAttributes() {
    assertThat(UserContext.anonymous().hasKey()).isFalse();
    assertThat(UserContext.anonymous().attributes()).isEmpty();
    assertThat(UserContext.fromRaw(null, null).attributes()).isEmpty();
  }
}
