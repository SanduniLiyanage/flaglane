package io.github.sanduniliyanage.flaglane.account.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /api/auth/register}. */
public record RegisterRequest(
    @Schema(example = "amara@example.com", description = "Stored lower-cased; must be unused")
        @NotBlank
        @Email
        @Size(max = 254)
        String email,
    @Schema(
            description =
                "At least 15 characters and at most 72 bytes of UTF-8, the most bcrypt reads",
            format = "password")
        @AcceptablePassword
        String password,
    @Schema(example = "Amara Perera", nullable = true) @Size(max = 100) String displayName) {

  @Override
  public String toString() {
    return "RegisterRequest[email=" + email + ", password=<redacted>]";
  }
}
