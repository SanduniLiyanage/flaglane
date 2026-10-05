package io.github.sanduniliyanage.flaglane.project.web;

import io.github.sanduniliyanage.flaglane.project.domain.Project;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** A project. Its key is how every other endpoint names it. */
public record ProjectResponse(
    @Schema(example = "storefront") String key,
    @Schema(example = "Storefront") String name,
    Instant createdAt) {

  static ProjectResponse from(Project project) {
    return new ProjectResponse(project.key(), project.name(), project.createdAt());
  }
}
