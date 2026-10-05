package io.github.sanduniliyanage.flaglane.serving.web;

import io.github.sanduniliyanage.flaglane.evaluation.Reason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The answer to {@code POST /sdk/evaluate}: one result per flag asked about, by flag key. */
public record EvaluateResponse(
    @Schema(example = "production") String environment,
    @Schema(description = "The ruleset_version the answers were computed from") long version,
    Map<String, Result> results) {

  public EvaluateResponse {
    results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
  }

  /** One flag's value and the step of the resolution order that produced it. */
  public record Result(boolean value, Reason reason) {}
}
