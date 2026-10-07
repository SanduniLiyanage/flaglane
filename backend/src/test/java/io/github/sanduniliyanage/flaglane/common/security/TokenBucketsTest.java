package io.github.sanduniliyanage.flaglane.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sanduniliyanage.flaglane.common.security.TokenBuckets.Decision;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TokenBucketsTest {

  private final MovableClock clock = new MovableClock(Instant.parse("2026-10-06T09:00:00Z"));

  @Test
  void aFullBucketAllowsPerMinuteRequestsAtOnceThenRefusesForOneInterval() {
    TokenBuckets buckets = new TokenBuckets(10, 100, clock);

    for (int i = 0; i < 10; i++) {
      assertThat(buckets.tryTake("203.0.113.7").allowed()).as("request %d", i + 1).isTrue();
    }
    Decision eleventh = buckets.tryTake("203.0.113.7");

    assertThat(eleventh.allowed()).isFalse();
    assertThat(eleventh.retryAfter()).isEqualTo(Duration.ofSeconds(6));
    assertThat(eleventh.retryAfterSeconds()).isEqualTo(6);
  }

  @Test
  void anEmptyBucketRefillsOneRequestPerInterval() {
    TokenBuckets buckets = new TokenBuckets(10, 100, clock);
    drain(buckets, "203.0.113.7", 10);

    clock.advance(Duration.ofMillis(5_999));
    Decision early = buckets.tryTake("203.0.113.7");
    clock.advance(Duration.ofMillis(1));
    Decision onTime = buckets.tryTake("203.0.113.7");
    Decision next = buckets.tryTake("203.0.113.7");

    assertThat(early.allowed()).isFalse();
    assertThat(early.retryAfter()).isEqualTo(Duration.ofMillis(1));
    assertThat(early.retryAfterSeconds()).as("Retry-After rounds up, never to 0").isEqualTo(1);
    assertThat(onTime.allowed()).isTrue();
    assertThat(next.allowed()).isFalse();
  }

  @Test
  void aRefusedRequestDoesNotPushTheWaitFurtherOut() {
    TokenBuckets buckets = new TokenBuckets(10, 100, clock);
    drain(buckets, "203.0.113.7", 10);

    for (int i = 0; i < 50; i++) {
      buckets.tryTake("203.0.113.7");
    }
    clock.advance(Duration.ofSeconds(6));

    assertThat(buckets.tryTake("203.0.113.7").allowed()).isTrue();
  }

  @Test
  void aBucketIdleForAMinuteIsFullAgain() {
    TokenBuckets buckets = new TokenBuckets(10, 100, clock);
    drain(buckets, "203.0.113.7", 10);

    clock.advance(Duration.ofMinutes(1));

    assertThat(drain(buckets, "203.0.113.7", 11)).isEqualTo(10);
  }

  @Test
  void keysHaveSeparateBuckets() {
    TokenBuckets buckets = new TokenBuckets(10, 100, clock);
    drain(buckets, "203.0.113.7", 10);

    assertThat(buckets.tryTake("203.0.113.7").allowed()).isFalse();
    assertThat(buckets.tryTake("198.51.100.20").allowed()).isTrue();
  }

  @Test
  void aClockThatStepsBackGrantsNothingExtra() {
    TokenBuckets buckets = new TokenBuckets(10, 100, clock);
    drain(buckets, "203.0.113.7", 10);

    clock.advance(Duration.ofMinutes(-5));

    assertThat(buckets.tryTake("203.0.113.7").allowed()).isFalse();
  }

  @Test
  void aFullTableDropsFullBucketsToMakeRoom() {
    TokenBuckets buckets = new TokenBuckets(10, 3, clock);
    buckets.tryTake("a");
    buckets.tryTake("b");
    buckets.tryTake("c");

    clock.advance(Duration.ofSeconds(6));

    assertThat(buckets.tryTake("d").allowed()).isTrue();
    assertThat(buckets.size()).isEqualTo(1);
  }

  @Test
  void aFullTableOfDrainingBucketsRefusesNewcomersAndKeepsLimitingTheKeysItHolds() {
    TokenBuckets buckets = new TokenBuckets(10, 3, clock);
    drain(buckets, "a", 10);
    buckets.tryTake("b");
    buckets.tryTake("c");

    Decision newcomer = buckets.tryTake("d");

    assertThat(newcomer.allowed()).isFalse();
    assertThat(newcomer.retryAfter()).isEqualTo(Duration.ofSeconds(6));
    assertThat(buckets.tryTake("a").allowed()).as("a is still limited, not forgotten").isFalse();
    assertThat(buckets.tryTake("b").allowed()).as("b is still served").isTrue();
    assertThat(buckets.size()).isEqualTo(3);
  }

  @Test
  void concurrentRequestsForOneKeyTakeExactlyTheTokensThereAre() throws Exception {
    TokenBuckets buckets = new TokenBuckets(100, 100, clock);
    AtomicInteger allowed = new AtomicInteger();
    List<Callable<Void>> tasks = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      tasks.add(
          () -> {
            for (int j = 0; j < 50; j++) {
              if (buckets.tryTake("203.0.113.7").allowed()) {
                allowed.incrementAndGet();
              }
            }
            return null;
          });
    }

    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      for (Future<Void> done : pool.invokeAll(tasks)) {
        done.get();
      }
    }

    assertThat(allowed.get()).isEqualTo(100);
  }

  @Test
  void aRequestMayTakeSeveralTokensAndARefusalTakesNone() {
    TokenBuckets buckets = new TokenBuckets(30, 100, clock);

    assertThat(buckets.tryTake("key", 10).allowed()).isTrue();
    assertThat(buckets.tryTake("key", 10).allowed()).isTrue();
    assertThat(drain(buckets, "key", 10)).isEqualTo(10);
    Decision full = buckets.tryTake("key", 10);
    Decision single = buckets.tryTake("key");

    assertThat(full.allowed()).isFalse();
    assertThat(full.retryAfter()).as("ten tokens at two seconds each").isEqualTo(seconds(20));
    assertThat(single.allowed()).isFalse();
    assertThat(single.retryAfter()).isEqualTo(seconds(2));
  }

  @Test
  void aBucketWithTooFewTokensForALargeRequestStillServesSmallOnes() {
    TokenBuckets buckets = new TokenBuckets(30, 100, clock);
    buckets.tryTake("key", 10);
    buckets.tryTake("key", 10);
    drain(buckets, "key", 5);

    assertThat(buckets.tryTake("key", 10).allowed()).as("5 tokens left, 10 asked").isFalse();
    assertThat(drain(buckets, "key", 6)).isEqualTo(5);
  }

  @Test
  void aRequestTakesFromOneTokenToAWholeBucket() {
    TokenBuckets buckets = new TokenBuckets(30, 100, clock);

    assertThatThrownBy(() -> buckets.tryTake("key", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> buckets.tryTake("key", 31))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(buckets.tryTake("key", 30).allowed()).isTrue();
  }

  @Test
  void anIntervalShorterThanTheClocksMillisecondRefillsByTheElapsedTime() {
    // Six million a minute: a token every 10 µs, a hundred every millisecond.
    TokenBuckets buckets = new TokenBuckets(TokenBuckets.MAX_PER_MINUTE, 100, clock);
    drain(buckets, "key", TokenBuckets.MAX_PER_MINUTE);
    Decision empty = buckets.tryTake("key");

    clock.advance(Duration.ofMillis(1));

    assertThat(empty.allowed()).isFalse();
    assertThat(empty.retryAfter()).isEqualTo(Duration.ofNanos(10_000));
    assertThat(empty.retryAfterSeconds()).isEqualTo(1);
    assertThat(drain(buckets, "key", 101)).isEqualTo(100);
  }

  @Test
  void aForgottenKeyStartsAgainWithAFullBucket() {
    TokenBuckets buckets = new TokenBuckets(10, 100, clock);
    drain(buckets, "key", 10);

    buckets.forget("key");

    assertThat(buckets.holds("key")).isFalse();
    assertThat(drain(buckets, "key", 11)).isEqualTo(10);
  }

  @Test
  void droppingFullBucketsKeepsTheOnesStillRefilling() {
    TokenBuckets buckets = new TokenBuckets(10, 100, clock);
    buckets.tryTake("refilled");
    clock.advance(Duration.ofSeconds(6));
    drain(buckets, "draining", 10);

    buckets.dropFull();

    assertThat(buckets.holds("refilled")).isFalse();
    assertThat(buckets.holds("draining")).isTrue();
    assertThat(buckets.tryTake("draining").allowed()).as("still limited").isFalse();
  }

  @Test
  void aLimitOutsideOneToSixMillionTokensAMinuteIsRefused() {
    assertThatThrownBy(() -> new TokenBuckets(0, 100, clock))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBuckets(TokenBuckets.MAX_PER_MINUTE + 1, 100, clock))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Duration seconds(long seconds) {
    return Duration.ofSeconds(seconds);
  }

  /** Makes up to {@code attempts} requests and returns how many were allowed. */
  private static int drain(TokenBuckets buckets, String key, int attempts) {
    int allowed = 0;
    for (int i = 0; i < attempts; i++) {
      if (buckets.tryTake(key).allowed()) {
        allowed++;
      }
    }
    return allowed;
  }

  private static final class MovableClock extends Clock {

    private volatile Instant now;

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
