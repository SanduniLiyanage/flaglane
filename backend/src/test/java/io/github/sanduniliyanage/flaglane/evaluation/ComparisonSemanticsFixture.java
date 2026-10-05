package io.github.sanduniliyanage.flaglane.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * Suite 4a's cases, read from {@code fixtures/comparison-semantics.json} so that the Java engine
 * and the TypeScript SDK assert against the same rows rather than two hand-kept copies.
 */
final class ComparisonSemanticsFixture {

  private static final String RESOURCE = "/fixtures/comparison-semantics.json";

  private ComparisonSemanticsFixture() {}

  /** One row: a single rule, a set of attributes, and whether the rule matches them. */
  record Case(
      String name,
      String attribute,
      String operator,
      List<?> matchValues,
      Map<String, ?> attributes,
      String expected) {

    TargetingRule rule() {
      return new TargetingRule(0, attribute, operator, matchValues, true);
    }

    UserContext user() {
      return UserContext.fromRaw("u-1", attributes);
    }

    @Override
    public String toString() {
      return name;
    }
  }

  @SuppressWarnings("unchecked")
  static List<Case> cases() {
    try (InputStream in = ComparisonSemanticsFixture.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is not on the test classpath");
      }
      Map<String, Object> document = new ObjectMapper().readValue(in, new TypeReference<>() {});
      List<Map<String, Object>> rows = (List<Map<String, Object>>) document.get("cases");
      return rows.stream()
          .map(
              row ->
                  new Case(
                      (String) row.get("name"),
                      row.containsKey("attribute") ? (String) row.get("attribute") : "attr",
                      (String) row.get("operator"),
                      (List<?>) row.get("matchValues"),
                      (Map<String, ?>) row.get("attributes"),
                      (String) row.get("expected")))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
