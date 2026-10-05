package io.github.sanduniliyanage.flaglane.evaluation;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * At most one warning per flag key per minute per process (FR-EVL-006). Without it, a broken rule
 * on a busy flag is an unbounded log write on the hot path.
 *
 * <p>Flag keys reach this from callers, including keys for flags that do not exist, so the state it
 * keeps is bounded twice over: keys are truncated to the longest a real flag key can be, and no
 * more than {@value #MAX_TRACKED_KEYS} are tracked. Beyond that, warnings for new keys are dropped
 * until a minute has passed and expired entries can be pruned, so a flood of junk keys costs a
 * bounded map and at most one scan of it per minute.
 */
final class WarningRateLimiter {

  static final Duration INTERVAL = Duration.ofMinutes(1);
  static final int MAX_TRACKED_KEYS = 10_000;

  /** Flag keys are at most 63 characters (docs/DATABASE.md); one more marks a truncation. */
  static final int MAX_KEY_LENGTH = 64;

  private static final long INTERVAL_MILLIS = INTERVAL.toMillis();
  private static final long NEVER = Long.MIN_VALUE;

  private final Clock clock;
  private final ConcurrentHashMap<String, Long> lastWarnedAt = new ConcurrentHashMap<>();
  private final AtomicLong lastPrunedAt = new AtomicLong(NEVER);

  WarningRateLimiter(Clock clock) {
    this.clock = clock;
  }

  /** Whether a warning for this flag key may be written now. Records it if so. */
  boolean tryAcquire(String flagKey) {
    String key = trackedKey(flagKey);
    long now = clock.millis();
    if (lastWarnedAt.size() >= MAX_TRACKED_KEYS && !lastWarnedAt.containsKey(key)) {
      pruneExpired(now);
      if (lastWarnedAt.size() >= MAX_TRACKED_KEYS) {
        return false;
      }
    }
    boolean[] acquired = {false};
    lastWarnedAt.compute(
        key,
        (k, previous) -> {
          // A clock that moved backwards resets the window rather than silencing the key.
          if (previous == null || now - previous >= INTERVAL_MILLIS || now < previous) {
            acquired[0] = true;
            return now;
          }
          return previous;
        });
    return acquired[0];
  }

  int trackedKeys() {
    return lastWarnedAt.size();
  }

  private void pruneExpired(long now) {
    long last = lastPrunedAt.get();
    boolean due = last == NEVER || now - last >= INTERVAL_MILLIS || now < last;
    if (due && lastPrunedAt.compareAndSet(last, now)) {
      lastWarnedAt.values().removeIf(at -> now - at >= INTERVAL_MILLIS || now < at);
    }
  }

  private static String trackedKey(String flagKey) {
    if (flagKey == null) {
      return "";
    }
    return flagKey.length() > MAX_KEY_LENGTH ? flagKey.substring(0, MAX_KEY_LENGTH) : flagKey;
  }
}
