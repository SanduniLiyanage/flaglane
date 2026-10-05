package io.github.sanduniliyanage.flaglane.account.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/auth/login}. Not checked against the password policy: the account exists. */
public record LoginRequest(
    @Schema(example = "amara@example.com") @NotBlank String email,
    @Schema(format = "password") @NotBlank String password) {

  @Override
  public String toString() {
    return "LoginRequest[email=" + email + ", password=<redacted>]";
  }
}
