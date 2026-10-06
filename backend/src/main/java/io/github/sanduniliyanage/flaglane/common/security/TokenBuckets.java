package io.github.sanduniliyanage.flaglane.common.security;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory token buckets, one per key: a key may make {@code perMinute} requests at once, and one
 * more every {@code 60 / perMinute} seconds after that. NFR-SEC-005 names the algorithm for the
 * serving API; sign-in uses the same one (ADR-029).
 *
 * <p>Each bucket is held as a single time, the moment it will be full again, which is the generic
 * cell rate algorithm's form of a token bucket. A request moves that moment one interval later and
 * is refused if it would land more than a full bucket ahead of now. A bucket whose moment has
 * passed is full, so dropping it changes no answer, and that is what keeps the table small.
 *
 * <p>The table holds at most {@code maxKeys} buckets. When it is full, the full buckets are
 * dropped; if every bucket is still draining, a key the table does not hold is refused. Under a
 * flood of distinct keys that turns newcomers away rather than forgetting, and so resetting, the
 * keys it is already limiting. Buckets are per instance, which is exact while Flaglane runs as one
 * (ADR-013).
 */
public final class TokenBuckets {

  private static final Logger LOG = LoggerFactory.getLogger(TokenBuckets.class);
  private static final long MILLIS_PER_MINUTE = 60_000;

  /** Whether a request may proceed, and if not, how long until it may. */
  public record Decision(boolean allowed, Duration retryAfter) {

    static final Decision ALLOWED = new Decision(true, Duration.ZERO);

    /** Whole seconds, rounded up, for a {@code Retry-After} header: never 0 for a refusal. */
    public long retryAfterSeconds() {
      return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
  }

  private final Clock clock;
  private final long intervalMillis;
  private final long capacityMillis;
  private final int maxKeys;
  private final ConcurrentHashMap<String, Long> fullAt = new ConcurrentHashMap<>();
  private final AtomicLong lastFullWarning = new AtomicLong(-MILLIS_PER_MINUTE);

  /**
   * @param perMinute requests per minute per key, and the size of the burst a full bucket allows;
   *     from 1 to 60,000
   * @param maxKeys the most buckets held at once
   */
  public TokenBuckets(int perMinute, int maxKeys, Clock clock) {
    if (perMinute < 1 || perMinute > MILLIS_PER_MINUTE) {
      throw new IllegalArgumentException(
          "A rate limit is from 1 to 60000 requests a minute, not " + perMinute);
    }
    if (maxKeys < 1) {
      throw new IllegalArgumentException("maxKeys must be positive, not " + maxKeys);
    }
    this.clock = clock;
    this.intervalMillis = MILLIS_PER_MINUTE / perMinute;
    this.capacityMillis = intervalMillis * perMinute;
    this.maxKeys = maxKeys;
  }

  /** Takes a token from the key's bucket if it has one. Never blocks. */
  public Decision tryTake(String key) {
    long now = clock.millis();
    if (!fullAt.containsKey(key) && fullAt.size() >= maxKeys && !makeRoom(now)) {
      warnFull(now);
      return new Decision(false, Duration.ofMillis(intervalMillis));
    }
    long[] wait = {0};
    fullAt.compute(
        key,
        (k, previous) -> {
          long next = Math.max(previous == null ? now : previous, now) + intervalMillis;
          if (next - now > capacityMillis) {
            wait[0] = next - now - capacityMillis;
            return previous;
          }
          return next;
        });
    return wait[0] == 0 ? Decision.ALLOWED : new Decision(false, Duration.ofMillis(wait[0]));
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

  private void warnFull(long now) {
    long last = lastFullWarning.get();
    if (now - last >= MILLIS_PER_MINUTE && lastFullWarning.compareAndSet(last, now)) {
      LOG.warn(
          "Rate limit table is full with {} active keys; refusing keys it does not hold", maxKeys);
    }
  }
}
