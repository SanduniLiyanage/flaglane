package io.github.sanduniliyanage.flaglane.apikey.web;

import io.github.sanduniliyanage.flaglane.apikey.domain.KeyType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code POST .../environments/{envKey}/keys}. */
public record IssueApiKeyRequest(
    @Schema(example = "checkout-service") @NotBlank @Size(max = 100) String name,
    @Schema(
            description =
                "`server` reads every flag; `client` reads client-side-visible flags only, with"
                    + " no user overrides")
        @NotNull
        KeyType type) {}
