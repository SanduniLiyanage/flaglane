package io.github.sanduniliyanage.flaglane.project.web;

import io.github.sanduniliyanage.flaglane.project.domain.Environment;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** An environment. Its key names it within its project. */
public record EnvironmentResponse(
    @Schema(example = "production") String key,
    @Schema(example = "Production") String name,
    Instant createdAt) {

  static EnvironmentResponse from(Environment environment) {
    return new EnvironmentResponse(environment.key(), environment.name(), environment.createdAt());
  }
}
