package io.github.sanduniliyanage.flaglane.account.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** A registered account. Carries no credential. */
public record AccountResponse(
    UUID id,
    @Schema(example = "amara@example.com") String email,
    @Schema(nullable = true, example = "Amara Perera") String displayName,
    Instant createdAt) {}
