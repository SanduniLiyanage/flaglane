package io.github.sanduniliyanage.flaglane.streaming.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits and timings of {@code GET /sdk/stream} (FR-STR-002, FR-STR-004, ADR-039).
 *
 * @param maxPerKey open streams one key may hold, from {@code FLAGLANE_STREAM_MAX_PER_KEY}; one
 *     more is refused with 429, and its SDK polls instead
 * @param maxPerEnvironment open streams one environment may hold, every key's together, from {@code
 *     FLAGLANE_STREAM_MAX_PER_ENVIRONMENT}
 * @param heartbeat how often an idle stream gets a comment, so that a proxy closing quiet
 *     connections leaves it open; at most 30 seconds (FR-STR-002)
 * @param maxDuration how long a stream lasts before the server ends it and its SDK reconnects
 */
@ConfigurationProperties("flaglane.streaming")
public record StreamProperties(
    int maxPerKey, int maxPerEnvironment, Duration heartbeat, Duration maxDuration) {

  static final int MAX_STREAMS = 100_000;
  static final Duration MAX_HEARTBEAT = Duration.ofSeconds(30);

  public StreamProperties {
    if (maxPerKey < 1 || maxPerKey > MAX_STREAMS) {
      throw new IllegalArgumentException(
          "FLAGLANE_STREAM_MAX_PER_KEY must be from 1 to " + MAX_STREAMS + ", not " + maxPerKey);
    }
    if (maxPerEnvironment < 1 || maxPerEnvironment > MAX_STREAMS) {
      throw new IllegalArgumentException(
          "FLAGLANE_STREAM_MAX_PER_ENVIRONMENT must be from 1 to "
              + MAX_STREAMS
              + ", not "
              + maxPerEnvironment);
    }
    if (heartbeat == null || heartbeat.isNegative() || heartbeat.isZero()) {
      throw new IllegalArgumentException("flaglane.streaming.heartbeat must be positive");
    }
    if (heartbeat.compareTo(MAX_HEARTBEAT) > 0) {
      throw new IllegalArgumentException(
          "flaglane.streaming.heartbeat must be at most 30 seconds (FR-STR-002), not " + heartbeat);
    }
    if (maxDuration == null || maxDuration.compareTo(heartbeat) <= 0) {
      throw new IllegalArgumentException(
          "flaglane.streaming.max-duration must be longer than the heartbeat");
    }
  }
}
