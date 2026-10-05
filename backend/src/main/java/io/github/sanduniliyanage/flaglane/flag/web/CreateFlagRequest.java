package io.github.sanduniliyanage.flaglane.flag.web;

import io.github.sanduniliyanage.flaglane.common.validation.ResourceKey;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /api/projects/{projectKey}/flags}. */
public record CreateFlagRequest(
    @Schema(
            example = "new-checkout",
            description = "Unique in the project, archived flags included; can never change")
        @ResourceKey
        String key,
    @Schema(example = "New checkout") @NotBlank @Size(max = 100) String name,
    @Schema(nullable = true) @Size(max = 1000) String description,
    @Schema(
            description =
                "Whether client keys may read it. Defaults to false: browser keys are public",
            defaultValue = "false")
        Boolean clientSideVisible) {}
