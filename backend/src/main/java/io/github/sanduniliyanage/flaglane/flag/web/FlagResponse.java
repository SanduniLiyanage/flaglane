package io.github.sanduniliyanage.flaglane.flag.web;

import io.github.sanduniliyanage.flaglane.flag.domain.Flag;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** A flag. */
public record FlagResponse(
    @Schema(example = "new-checkout") String key,
    @Schema(example = "New checkout") String name,
    @Schema(nullable = true) String description,
    boolean clientSideVisible,
    @Schema(nullable = true, description = "Set while the flag is archived") Instant archivedAt,
    Instant createdAt) {

  static FlagResponse from(Flag flag) {
    return new FlagResponse(
        flag.key(),
        flag.name(),
        flag.description(),
        flag.clientSideVisible(),
        flag.archivedAt(),
        flag.createdAt());
  }
}
