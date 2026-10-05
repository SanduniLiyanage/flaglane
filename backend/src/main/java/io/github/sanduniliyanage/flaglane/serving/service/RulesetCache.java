package io.github.sanduniliyanage.flaglane.serving.service;

import io.github.sanduniliyanage.flaglane.project.domain.RulesetChanged;
import io.github.sanduniliyanage.flaglane.serving.domain.RulesetSnapshot;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Every environment's ruleset, in memory, which is all {@code /sdk/**} ever reads (NFR-PER-004).
 *
 * <ul>
 *   <li>Built for every environment before the application accepts requests.
 *   <li>Rebuilt for an environment after any transaction that changed it commits, and swapped in
 *       whole, so a reader sees the old snapshot or the new one and never a mixture (ADR-008).
 *   <li>Never moved backwards: of two rebuilds racing for one environment, the older version loses,
 *       whichever finishes last.
 *   <li>Reconciled against the database once a minute, so a rebuild that failed — the database was
 *       briefly unreachable — or a change made by another process is picked up.
 * </ul>
 *
 * <p>If the database goes away, the cache keeps serving what it has. That is the point of it.
 */
@Component
public class RulesetCache implements SmartInitializingSingleton {

  private static final Logger LOG = LoggerFactory.getLogger(RulesetCache.class);

  private final RulesetLoader loader;
  private final ConcurrentHashMap<UUID, RulesetSnapshot> snapshots = new ConcurrentHashMap<>();
  private volatile boolean ready;

  public RulesetCache(RulesetLoader loader) {
    this.loader = loader;
  }

  @Override
  public void afterSingletonsInstantiated() {
    loader.versions().keySet().forEach(this::rebuild);
    ready = true;
  }

  /** The environment's current snapshot, or empty if it has none. Lock-free. */
  public Optional<RulesetSnapshot> get(UUID environmentId) {
    return Optional.ofNullable(snapshots.get(environmentId));
  }

  /** Whether every environment has been loaded once; until then, serving answers 503. */
  public boolean isReady() {
    return ready;
  }

  public int size() {
    return snapshots.size();
  }

  /** Reloads one environment, or forgets it if it no longer exists. */
  public void rebuild(UUID environmentId) {
    Optional<RulesetSnapshot> loaded = loader.load(environmentId);
    if (loaded.isEmpty()) {
      snapshots.remove(environmentId);
      return;
    }
    snapshots.merge(
        environmentId,
        loaded.get(),
        (current, fresh) -> fresh.version() >= current.version() ? fresh : current);
  }

  /** Runs after the changing transaction commits, before its request returns. */
  @TransactionalEventListener
  public void changed(RulesetChanged event) {
    for (UUID environmentId : event.environmentIds()) {
      try {
        rebuild(environmentId);
      } catch (RuntimeException e) {
        LOG.warn(
            "Could not rebuild the ruleset of environment {}; the next reconciliation will",
            environmentId,
            e);
      }
    }
  }

  /** Brings every snapshot up to the database's version, and drops deleted environments. */
  @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
  public void reconcile() {
    Map<UUID, Long> versions;
    try {
      versions = loader.versions();
    } catch (RuntimeException e) {
      LOG.warn("Could not reconcile the ruleset cache; serving continues from it", e);
      return;
    }
    Set<UUID> stale = new HashSet<>();
    versions.forEach(
        (environmentId, version) -> {
          RulesetSnapshot current = snapshots.get(environmentId);
          if (current == null || current.version() != version) {
            stale.add(environmentId);
          }
        });
    // Cached environments the listing did not mention: rebuilt rather than dropped outright, so
    // one created after the listing was read is not lost.
    snapshots.keySet().stream().filter(id -> !versions.containsKey(id)).forEach(stale::add);
    for (UUID environmentId : stale) {
      try {
        rebuild(environmentId);
      } catch (RuntimeException e) {
        LOG.warn("Could not rebuild the ruleset of environment {}", environmentId, e);
        return;
      }
    }
  }
}
