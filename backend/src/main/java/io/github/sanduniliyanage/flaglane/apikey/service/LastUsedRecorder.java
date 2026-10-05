package io.github.sanduniliyanage.flaglane.apikey.service;

import io.github.sanduniliyanage.flaglane.apikey.persistence.ApiKeyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records when each key was last used, without a database write on the request (FR-KEY-006). A
 * request notes its key in memory; once a minute the notes are written, at most one update per key,
 * so a key used ten thousand times in a minute costs one row update and a serving request never
 * waits on the database.
 *
 * <p>If the database is unreachable the notes are kept and written by a later flush. The cost is a
 * {@code last_used_at} up to a minute stale, which is the accuracy FR-KEY-006 asks for.
 */
@Component
public class LastUsedRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(LastUsedRecorder.class);

  private final ApiKeyRepository keys;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final ConcurrentHashMap<UUID, Instant> pending = new ConcurrentHashMap<>();

  public LastUsedRecorder(ApiKeyRepository keys, TransactionTemplate transactions, Clock clock) {
    this.keys = keys;
    this.transactions = transactions;
    this.clock = clock;
  }

  /** Notes a use. Memory only; safe on the request path. */
  public void record(UUID keyId) {
    pending.put(keyId, clock.instant().truncatedTo(ChronoUnit.MICROS));
  }

  /** Writes and clears the notes. Runs every minute; tests call it directly. */
  @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
  public void flush() {
    for (Map.Entry<UUID, Instant> use : pending.entrySet()) {
      UUID keyId = use.getKey();
      Instant at = use.getValue();
      try {
        transactions.executeWithoutResult(status -> keys.markUsed(keyId, at));
        // Only forget the note if no newer use arrived while it was being written.
        pending.remove(keyId, at);
      } catch (RuntimeException e) {
        LOG.warn("Could not record last use of API key {}; will retry", keyId, e);
        return;
      }
    }
  }

  int pendingKeys() {
    return pending.size();
  }
}
