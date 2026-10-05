package io.github.sanduniliyanage.flaglane.targeting.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** {@code PUT .../config/{envKey}/rules}: the whole ordered list, first match wins. */
public record ReplaceRulesRequest(
    @Schema(
            description =
                "In priority order. An empty list removes every rule (ADR-023: at most 100)")
        @NotNull
        @Size(max = 100)
        List<@Valid @NotNull RuleRequest> rules) {

  public ReplaceRulesRequest {
    // Kept as received, nulls included, so validation can name a bad entry; unmodifiable after.
    rules = rules == null ? null : Collections.unmodifiableList(new ArrayList<>(rules));
  }
}
