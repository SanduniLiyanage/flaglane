package io.github.sanduniliyanage.flaglane.targeting.web;

import io.github.sanduniliyanage.flaglane.targeting.domain.UserOverride;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One entry of a {@code PUT .../overrides} body. */
public record OverrideRequest(
    @Schema(example = "u-1042") @NotBlank @Size(max = 256) String userKey, @NotNull Boolean value) {

  UserOverride toOverride() {
    return new UserOverride(userKey, value);
  }
}
