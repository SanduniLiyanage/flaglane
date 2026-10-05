package io.github.sanduniliyanage.flaglane.serving.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code POST /sdk/evaluate} (FR-SRV-002, docs/API.md). */
public record EvaluateRequest(
    @Schema(description = "Who to evaluate for. Optional: without it, evaluation is anonymous")
        @Valid
        Context context,
    @Schema(
            description =
                "The flags to evaluate, each with the value the caller has decided is safe")
        @NotNull
        @Size(min = 1, max = 100)
        List<@Valid @NotNull FlagRequest> flags) {

  public EvaluateRequest {
    flags = flags == null ? null : Collections.unmodifiableList(new ArrayList<>(flags));
  }

  /**
   * @param key optional; without it, overrides and the rollout are skipped and rules still apply
   *     (FR-EVL-005)
   * @param attributes strings, numbers and booleans; anything else is ignored (FR-RUL-006)
   */
  public record Context(
      @Schema(example = "u-1042", nullable = true) String key,
      @Schema(example = "{\"country\": \"LK\", \"plan\": \"pro\"}", nullable = true)
          Map<String, Object> attributes) {

    public Context {
      attributes =
          attributes == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
  }

  /**
   * @param fallback required: what an unknown flag, a flag this key may not read, or an internal
   *     error resolves to (FR-EVL-007)
   */
  public record FlagRequest(
      @Schema(example = "new-checkout") @NotBlank String key, @NotNull Boolean fallback) {}
}
