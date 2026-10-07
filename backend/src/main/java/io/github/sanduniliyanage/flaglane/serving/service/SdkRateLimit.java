package io.github.sanduniliyanage.flaglane.serving.service;

import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKeyEvents;
import io.github.sanduniliyanage.flaglane.common.security.TokenBuckets;
import io.github.sanduniliyanage.flaglane.common.security.TokenBuckets.Decision;
import java.time.Clock;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The serving API's allowance per API key (NFR-SEC-005, ADR-035): {@code perMinute} requests a
 * minute, where a {@code 304} counts as a tenth of one. An SDK polling every five seconds is told
 * "unchanged" twelve times a minute, which costs 1.2 requests, so at the default of 600 one key
 * serves 500 polling processes, or 600 ruleset downloads or evaluations a minute, or a mix of both.
 * A key that has been idle may spend its whole minute at once, which is what a fleet restarting
 * together does.
 *
 * <p>Buckets are kept by key id. A revoked key's bucket is dropped when the revoke commits, and
 * every minute any bucket that has refilled is dropped as well, which also catches one recreated by
 * a request that authenticated just before the revoke. The table therefore holds only keys used in
 * the last minute that can still be used. Per instance, which is exact while Flaglane runs as one
 * (ADR-013).
 */
@Component
public class SdkRateLimit {

  /** What an answer costs, in tenths of a request. */
  public enum Cost {
    /** A {@code 304}: headers only, no ruleset. */
    NOT_MODIFIED(1),
    /** A ruleset, an evaluation, or anything else. */
    FULL(TENTHS);

    private final int tokens;

    Cost(int tokens) {
      this.tokens = tokens;
    }
  }

  /**
   * Keys tracked at once. Only an authenticated request is charged, so every bucket belongs to a
   * live key used within the last minute; this is far above what one instance serves.
   */
  static final int MAX_KEYS = 100_000;

  private static final int TENTHS = 10;

  private final TokenBuckets buckets;

  public SdkRateLimit(SdkRateLimitProperties properties, Clock clock) {
    this.buckets = new TokenBuckets(properties.perMinute() * TENTHS, MAX_KEYS, clock);
  }

  /** Charges a request to its key's allowance, if the key has enough left. Never blocks. */
  public Decision charge(UUID keyId, Cost cost) {
    return buckets.tryTake(keyId.toString(), cost.tokens);
  }

  /** A revoked key is refused before it reaches here, so its bucket would only take up room. */
  @TransactionalEventListener
  public void revoked(ApiKeyEvents.Revoked event) {
    buckets.forget(event.credential().keyId().toString());
  }

  /** Drops the buckets that have refilled. Runs every minute; tests call it directly. */
  @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
  public void dropRefilled() {
    buckets.dropFull();
  }

  /** Whether a bucket is held for the key. */
  public boolean tracks(UUID keyId) {
    return buckets.holds(keyId.toString());
  }
}
