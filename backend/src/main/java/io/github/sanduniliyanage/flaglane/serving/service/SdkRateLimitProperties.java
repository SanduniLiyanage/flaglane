package io.github.sanduniliyanage.flaglane.serving.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How many requests one API key may make to the serving API (NFR-SEC-005, ADR-035), from {@code
 * FLAGLANE_SDK_RATE_LIMIT_PER_MINUTE}.
 *
 * @param perMinute requests a minute per key, a {@code 304} counting as a tenth of one; also how
 *     many a key that has been idle may make at once. From 1 to 600,000
 */
@ConfigurationProperties("flaglane.security.sdk-rate-limit")
public record SdkRateLimitProperties(int perMinute) {

  /** The most a key may be allowed: 6,000,000 tenths, the finest the buckets keep. */
  static final int MAX_PER_MINUTE = 600_000;

  public SdkRateLimitProperties {
    if (perMinute < 1 || perMinute > MAX_PER_MINUTE) {
      throw new IllegalArgumentException(
          "FLAGLANE_SDK_RATE_LIMIT_PER_MINUTE must be from 1 to "
              + MAX_PER_MINUTE
              + ", not "
              + perMinute);
    }
  }
}
