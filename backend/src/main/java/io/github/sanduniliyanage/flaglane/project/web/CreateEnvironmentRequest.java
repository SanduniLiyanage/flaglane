package io.github.sanduniliyanage.flaglane.project.web;

import io.github.sanduniliyanage.flaglane.common.validation.ResourceKey;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /api/projects/{projectKey}/environments}. */
public record CreateEnvironmentRequest(
    @Schema(
            example = "qa",
            description = "Unique within the project",
            requiredMode = Schema.RequiredMode.REQUIRED,
            pattern = ResourceKey.REGEX)
        @ResourceKey
        String key,
    @Schema(example = "QA") @NotBlank @Size(max = 100) String name) {}
