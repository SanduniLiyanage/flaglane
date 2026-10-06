package io.github.sanduniliyanage.flaglane.account.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How many registration and sign-in requests one client address may make (ADR-029), from {@code
 * FLAGLANE_AUTH_RATE_LIMIT_PER_MINUTE}.
 *
 * @param perMinute requests a minute, which is also how many a client that has been idle may make
 *     at once; from 1 to 60,000
 */
@ConfigurationProperties("flaglane.security.auth-rate-limit")
public record AuthRateLimitProperties(int perMinute) {

  public AuthRateLimitProperties {
    if (perMinute < 1 || perMinute > 60_000) {
      throw new IllegalArgumentException(
          "FLAGLANE_AUTH_RATE_LIMIT_PER_MINUTE must be from 1 to 60000, not " + perMinute);
    }
  }
}
