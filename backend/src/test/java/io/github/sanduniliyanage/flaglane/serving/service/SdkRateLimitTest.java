package io.github.sanduniliyanage.flaglane.serving.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKeyEvents;
import io.github.sanduniliyanage.flaglane.apikey.domain.KeyType;
import io.github.sanduniliyanage.flaglane.apikey.domain.SdkCredential;
import io.github.sanduniliyanage.flaglane.common.security.TokenBuckets.Decision;
import io.github.sanduniliyanage.flaglane.serving.service.SdkRateLimit.Cost;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What a request costs, and when a key's bucket goes away (NFR-SEC-005, ADR-035). */
class SdkRateLimitTest {

  private final MovableClock clock = new MovableClock(Instant.parse("2026-10-07T09:00:00Z"));
  private final UUID key = UUID.randomUUID();

  @Test
  void aKeyMakesPerMinuteFullRequestsAtOnceThenWaitsForOne() {
    SdkRateLimit limit = limit(600);

    assertThat(charge(limit, Cost.FULL, 601)).isEqualTo(600);
    Decision refused = limit.charge(key, Cost.FULL);

    assertThat(refused.allowed()).isFalse();
    assertThat(refused.retryAfter()).as("one request at 600 a minute").isEqualTo(millis(100));
  }

  @Test
  void aNotModifiedCostsATenthOfAFullAnswer() {
    SdkRateLimit limit = limit(600);

    assertThat(charge(limit, Cost.NOT_MODIFIED, 6_001)).isEqualTo(6_000);
  }

  @Test
  void atTheDefaultOneKeyServesFiveHundredProcessesPollingEveryFiveSeconds() {
    SdkRateLimit limit = limit(600);
    // Twelve polls a minute each, all unchanged, spread over the minute.
    int polls = 500 * 12;
    int allowed = 0;
    for (int poll = 0; poll < polls; poll++) {
      clock.advance(Duration.ofNanos(60_000_000_000L / polls));
      if (limit.charge(key, Cost.NOT_MODIFIED).allowed()) {
        allowed++;
      }
    }

    assertThat(allowed).isEqualTo(polls);
  }

  @Test
  void aKeyWithTooLittleLeftForAFullAnswerCanStillBeToldNothingChanged() {
    SdkRateLimit limit = limit(1);
    charge(limit, Cost.NOT_MODIFIED, 5);

    assertThat(limit.charge(key, Cost.FULL).allowed()).isFalse();
    assertThat(charge(limit, Cost.NOT_MODIFIED, 6)).isEqualTo(5);
  }

  @Test
  void keysHaveSeparateAllowances() {
    SdkRateLimit limit = limit(1);
    limit.charge(key, Cost.FULL);

    assertThat(limit.charge(key, Cost.FULL).allowed()).isFalse();
    assertThat(limit.charge(UUID.randomUUID(), Cost.FULL).allowed()).isTrue();
  }

  @Test
  void aRevokedKeysBucketIsDropped() {
    SdkRateLimit limit = limit(600);
    limit.charge(key, Cost.FULL);

    limit.revoked(new ApiKeyEvents.Revoked("hash", credential(key)));

    assertThat(limit.tracks(key)).isFalse();
  }

  @Test
  void revokingAnotherKeyLeavesThisOneLimited() {
    SdkRateLimit limit = limit(1);
    limit.charge(key, Cost.FULL);

    limit.revoked(new ApiKeyEvents.Revoked("hash", credential(UUID.randomUUID())));

    assertThat(limit.tracks(key)).isTrue();
    assertThat(limit.charge(key, Cost.FULL).allowed()).isFalse();
  }

  @Test
  void aBucketThatHasRefilledIsDroppedByTheMinutesSweep() {
    SdkRateLimit limit = limit(600);
    UUID idle = UUID.randomUUID();
    limit.charge(idle, Cost.FULL);
    clock.advance(Duration.ofMillis(100));
    charge(limit, Cost.FULL, 2);

    limit.dropRefilled();

    assertThat(limit.tracks(idle)).isFalse();
    assertThat(limit.tracks(key)).as("still refilling").isTrue();
  }

  @Test
  void aLimitOutsideOneToSixHundredThousandAMinuteIsRefused() {
    assertThatThrownBy(() -> new SdkRateLimitProperties(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FLAGLANE_SDK_RATE_LIMIT_PER_MINUTE");
    assertThatThrownBy(() -> new SdkRateLimitProperties(600_001))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(limit(600_000).charge(key, Cost.NOT_MODIFIED).allowed()).isTrue();
  }

  private SdkRateLimit limit(int perMinute) {
    return new SdkRateLimit(new SdkRateLimitProperties(perMinute), clock);
  }

  /** Makes up to {@code attempts} requests at one instant and returns how many were allowed. */
  private int charge(SdkRateLimit limit, Cost cost, int attempts) {
    int allowed = 0;
    for (int i = 0; i < attempts; i++) {
      if (limit.charge(key, cost).allowed()) {
        allowed++;
      }
    }
    return allowed;
  }

  private static SdkCredential credential(UUID keyId) {
    return new SdkCredential(keyId, UUID.randomUUID(), KeyType.SERVER);
  }

  private static Duration millis(long millis) {
    return Duration.ofMillis(millis);
  }

  private static final class MovableClock extends Clock {

    private Instant now;

    MovableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
