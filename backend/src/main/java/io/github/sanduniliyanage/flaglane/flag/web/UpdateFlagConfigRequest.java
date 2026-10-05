package io.github.sanduniliyanage.flaglane.flag.web;

import io.github.sanduniliyanage.flaglane.common.validation.ResourceKey;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * {@code PATCH .../flags/{flagKey}/config/{envKey}}. Fields left out are left unchanged. There is
 * no off value: a disabled flag returns {@code false}, and that is not configurable (ADR-009).
 */
public record UpdateFlagConfigRequest(
    @Schema(nullable = true, description = "The kill switch. Disabled returns false to everyone")
        Boolean enabled,
    @Schema(
            nullable = true,
            description =
                "Returned when the flag is enabled and nothing else matched. While true, the"
                    + " rollout has no effect: everyone it does not reach gets true anyway")
        Boolean fallthroughValue,
    @Schema(
            nullable = true,
            minimum = "0",
            maximum = "100",
            description = "Whole percent of users, stored as basis points (ADR-011)")
        @Min(0)
        @Max(100)
        Integer rolloutPercentage,
    @Schema(
            nullable = true,
            description =
                "Changing the salt re-buckets every user of this flag: who has it at a given"
                    + " percentage changes. Give flags the same salt to roll them out to the same"
                    + " users (ADR-010)")
        @Pattern(regexp = ResourceKey.REGEX)
        String rolloutSalt) {}
