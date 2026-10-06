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
  void aLimitOutsideOneToSixtyThousandAMinuteIsRefused() {
    assertThatThrownBy(() -> new TokenBuckets(0, 100, clock))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBuckets(60_001, 100, clock))
        .isInstanceOf(IllegalArgumentException.class);
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
