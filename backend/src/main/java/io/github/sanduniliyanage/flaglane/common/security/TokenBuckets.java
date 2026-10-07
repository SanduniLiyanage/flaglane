package io.github.sanduniliyanage.flaglane.common.security;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory token buckets, one per key: a key holds at most {@code perMinute} tokens, and gets one
 * back every {@code 60 / perMinute} seconds. A request takes one token, or several where some
 * requests cost more than others. NFR-SEC-005 names the algorithm for the serving API (ADR-035);
 * sign-in uses the same one (ADR-029).
 *
 * <p>Each bucket is held as a single time, the moment it will be full again, which is the generic
 * cell rate algorithm's form of a token bucket. Taking a token moves that moment one interval
 * later, and a request is refused if it would land more than a full bucket ahead of now. A bucket
 * whose moment has passed is full, so dropping it changes no answer, and that is what keeps the
 * table small. Moments are kept in nanoseconds, so an interval may be shorter than the clock's
 * millisecond.
 *
 * <p>The table holds at most {@code maxKeys} buckets. When it is full, the full buckets are
 * dropped; if every bucket is still draining, a key the table does not hold is refused. Under a
 * flood of distinct keys that turns newcomers away rather than forgetting, and so resetting, the
 * keys it is already limiting. Buckets are per instance, which is exact while Flaglane runs as one
 * (ADR-013).
 */
public final class TokenBuckets {

  /** The most tokens a minute a table may hand out: one every 10 µs. */
  public static final int MAX_PER_MINUTE = 6_000_000;

  private static final Logger LOG = LoggerFactory.getLogger(TokenBuckets.class);
  private static final long MILLIS_PER_MINUTE = 60_000;
  private static final long NANOS_PER_MILLI = 1_000_000;
  private static final long NANOS_PER_MINUTE = MILLIS_PER_MINUTE * NANOS_PER_MILLI;

  /** Whether a request may proceed, and if not, how long until it may. */
  public record Decision(boolean allowed, Duration retryAfter) {

    static final Decision ALLOWED = new Decision(true, Duration.ZERO);

    /** Whole seconds, rounded up, for a {@code Retry-After} header: never 0 for a refusal. */
    public long retryAfterSeconds() {
      long seconds = retryAfter.getSeconds() + (retryAfter.getNano() > 0 ? 1 : 0);
      return Math.max(1, seconds);
    }
  }

  private final Clock clock;
  private final int perMinute;
  private final long intervalNanos;
  private final long capacityNanos;
  private final int maxKeys;
  private final ConcurrentHashMap<String, Long> fullAt = new ConcurrentHashMap<>();
  private final AtomicLong lastFullWarning = new AtomicLong(-MILLIS_PER_MINUTE);

  /**
   * @param perMinute tokens a key gets back each minute, and the most it holds; from 1 to {@link
   *     #MAX_PER_MINUTE}
   * @param maxKeys the most buckets held at once
   */
  public TokenBuckets(int perMinute, int maxKeys, Clock clock) {
    if (perMinute < 1 || perMinute > MAX_PER_MINUTE) {
      throw new IllegalArgumentException(
          "A rate limit is from 1 to " + MAX_PER_MINUTE + " tokens a minute, not " + perMinute);
    }
    if (maxKeys < 1) {
      throw new IllegalArgumentException("maxKeys must be positive, not " + maxKeys);
    }
    this.clock = clock;
    this.perMinute = perMinute;
    this.intervalNanos = NANOS_PER_MINUTE / perMinute;
    this.capacityNanos = intervalNanos * perMinute;
    this.maxKeys = maxKeys;
  }

  /** Takes one token from the key's bucket if it has one. Never blocks. */
  public Decision tryTake(String key) {
    return tryTake(key, 1);
  }

  /**
   * Takes {@code tokens} from the key's bucket if it holds that many, and none if it does not: a
   * refused request costs nothing. Never blocks.
   *
   * @param tokens from 1 to the bucket's size
   */
  public Decision tryTake(String key, int tokens) {
    if (tokens < 1 || tokens > perMinute) {
      throw new IllegalArgumentException(
          "A request takes from 1 to " + perMinute + " tokens, not " + tokens);
    }
    long nowMillis = clock.millis();
    long now = nowMillis * NANOS_PER_MILLI;
    long cost = intervalNanos * tokens;
    if (!fullAt.containsKey(key) && fullAt.size() >= maxKeys && !makeRoom(now)) {
      warnFull(nowMillis);
      return new Decision(false, Duration.ofNanos(cost));
    }
    long[] wait = {0};
    fullAt.compute(
        key,
        (k, previous) -> {
          long next = Math.max(previous == null ? now : previous, now) + cost;
          if (next - now > capacityNanos) {
            wait[0] = next - now - capacityNanos;
            return previous;
          }
          return next;
        });
    return wait[0] == 0 ? Decision.ALLOWED : new Decision(false, Duration.ofNanos(wait[0]));
  }

  /**
   * Drops the key's bucket. For a key that can make no more requests, such as a revoked API key: if
   * the key did come back it would start with a full bucket.
   */
  public void forget(String key) {
    fullAt.remove(key);
  }

  /** Drops every bucket that has refilled, which changes no answer. */
  public void dropFull() {
    makeRoom(clock.millis() * NANOS_PER_MILLI);
  }

  /** Whether the table holds a bucket for the key, refilled or not. */
  public boolean holds(String key) {
    return fullAt.containsKey(key);
  }

  int size() {
    return fullAt.size();
  }

  /** Drops every full bucket; true if that made room. Removes a bucket only if it is unchanged. */
  private boolean makeRoom(long now) {
    fullAt.forEach(
        (key, moment) -> {
          if (moment <= now) {
            fullAt.remove(key, moment);
          }
        });
    return fullAt.size() < maxKeys;
  }

  private void warnFull(long nowMillis) {
    long last = lastFullWarning.get();
    if (nowMillis - last >= MILLIS_PER_MINUTE && lastFullWarning.compareAndSet(last, nowMillis)) {
      LOG.warn(
          "Rate limit table is full with {} active keys; refusing keys it does not hold", maxKeys);
    }
  }
}
