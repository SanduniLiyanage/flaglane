package io.github.sanduniliyanage.flaglane.account.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * The one access token a sign-in issues (FR-ACC-002). There is no refresh token: after {@code
 * expiresAt}, sign in again (ADR-012).
 */
public record AccessTokenResponse(
    @Schema(description = "Send as `Authorization: Bearer <accessToken>` on `/api/**`")
        String accessToken,
    @Schema(example = "Bearer") String tokenType,
    Instant expiresAt) {

  @Override
  public String toString() {
    return "AccessTokenResponse[accessToken=<redacted>, expiresAt=" + expiresAt + "]";
  }
}
