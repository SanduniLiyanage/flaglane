package io.github.sanduniliyanage.flaglane.project.web;

import io.github.sanduniliyanage.flaglane.common.validation.ResourceKey;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /api/projects}. */
public record CreateProjectRequest(
    @Schema(
            example = "storefront",
            description = "Unique across Flaglane; immutable",
            requiredMode = Schema.RequiredMode.REQUIRED,
            pattern = ResourceKey.REGEX)
        @ResourceKey
        String key,
    @Schema(example = "Storefront") @NotBlank @Size(max = 100) String name) {}
