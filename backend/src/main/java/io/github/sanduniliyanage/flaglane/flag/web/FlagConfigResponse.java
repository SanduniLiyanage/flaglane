package io.github.sanduniliyanage.flaglane.flag.web;

import io.github.sanduniliyanage.flaglane.flag.domain.FlagConfiguration;
import io.github.sanduniliyanage.flaglane.flag.domain.RolloutPercentage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** One flag's configuration in one environment. */
public record FlagConfigResponse(
    @Schema(example = "new-checkout") String flagKey,
    @Schema(example = "production") String environment,
    boolean enabled,
    @Schema(description = "Always false in v0.x (ADR-009)") boolean offValue,
    boolean fallthroughValue,
    @Schema(minimum = "0", maximum = "100") int rolloutPercentage,
    @Schema(example = "new-checkout") String rolloutSalt,
    Instant updatedAt) {

  static FlagConfigResponse from(FlagConfiguration config) {
    return new FlagConfigResponse(
        config.flagKey(),
        config.environmentKey(),
        config.enabled(),
        config.offValue(),
        config.fallthroughValue(),
        RolloutPercentage.fromBasisPoints(config.rolloutBasisPoints()),
        config.rolloutSalt(),
        config.updatedAt());
  }
}
