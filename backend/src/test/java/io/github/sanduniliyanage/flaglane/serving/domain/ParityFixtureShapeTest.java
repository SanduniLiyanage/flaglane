package io.github.sanduniliyanage.flaglane.serving.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The parity fixtures' rulesets are in exactly the shape {@code GET /sdk/config} serves. If the
 * served shape gains, loses or renames a field, this fails, so suite 9 cannot quietly go on testing
 * a wire format the server no longer sends.
 */
class ParityFixtureShapeTest {

  @Test
  void everyFixtureRulesetHasTheServedShape() throws Exception {
    JsonNode rulesets;
    try (InputStream in = getClass().getResourceAsStream("/fixtures/evaluation-parity.json")) {
      rulesets = new ObjectMapper().readTree(in).path("rulesets");
    }

    rulesets
        .properties()
        .forEach(
            entry -> {
              JsonNode ruleset = entry.getValue();
              assertThat(names(ruleset))
                  .as(entry.getKey())
                  .isEqualTo(components(ServedRuleset.class));
              boolean client = entry.getKey().endsWith("-client");
              for (JsonNode flag : ruleset.path("flags")) {
                Set<String> expected = components(ServedRuleset.Flag.class);
                if (client) {
                  // A client key's ruleset has no overrides field at all (FR-KEY-008).
                  expected.remove("overrides");
                }
                assertThat(names(flag)).as(flag.path("key").asText()).isEqualTo(expected);
                for (JsonNode rule : flag.path("rules")) {
                  assertThat(names(rule))
                      .as(flag.path("key").asText() + " rule")
                      .isEqualTo(components(ServedRuleset.Rule.class));
                }
                for (JsonNode override : flag.path("overrides")) {
                  assertThat(names(override))
                      .as(flag.path("key").asText() + " override")
                      .isEqualTo(components(ServedRuleset.UserOverride.class));
                }
              }
            });
  }

  private static Set<String> names(JsonNode node) {
    Set<String> names = new HashSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static Set<String> components(Class<?> record) {
    return new HashSet<>(
        Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList());
  }
}
