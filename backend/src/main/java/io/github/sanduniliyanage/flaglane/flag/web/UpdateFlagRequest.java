package io.github.sanduniliyanage.flaglane.flag.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /api/projects/{projectKey}/flags/{flagKey}}. Fields left out are left unchanged; an
 * empty description clears it. There is no key: a flag key never changes (FR-FLG-002).
 */
public record UpdateFlagRequest(
    @Schema(nullable = true) @Size(min = 1, max = 100) String name,
    @Schema(nullable = true, description = "Empty clears it") @Size(max = 1000) String description,
    @Schema(nullable = true) Boolean clientSideVisible) {}
