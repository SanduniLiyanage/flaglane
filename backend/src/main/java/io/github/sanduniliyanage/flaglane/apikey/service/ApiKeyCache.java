package io.github.sanduniliyanage.flaglane.apikey.service;

import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKeyEvents;
import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKeyFormat;
import io.github.sanduniliyanage.flaglane.apikey.domain.SdkCredential;
import io.github.sanduniliyanage.flaglane.apikey.persistence.ApiKeyEntity;
import io.github.sanduniliyanage.flaglane.apikey.persistence.ApiKeyRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Every live key's hash, in memory, so that authenticating an SDK request touches no database
 * (FR-KEY-007, NFR-PER-004). With the database down, serving carries on authenticating the keys it
 * knew; "rejected immediately" on revoke is a property of the invalidation below, not of a lookup
 * per request.
 *
 * <p>Loaded before the application accepts requests, and changed after the transaction that issued
 * or revoked a key on this instance commits. A key issued or revoked through another instance
 * arrives by reloading: when that instance announces it (ADR-041), and in any case once a minute.
 * Reads are lock-free; each write replaces the map, which costs a copy per change and nothing per
 * request.
 */
@Component
public class ApiKeyCache implements SmartInitializingSingleton {

  private static final Logger LOG = LoggerFactory.getLogger(ApiKeyCache.class);

  private final ApiKeyRepository keys;
  private final ApplicationEventPublisher events;
  private volatile Map<String, SdkCredential> byHash = Map.of();

  public ApiKeyCache(ApiKeyRepository keys, ApplicationEventPublisher events) {
    this.keys = keys;
    this.events = events;
  }

  @Override
  public void afterSingletonsInstantiated() {
    reload();
  }

  /** The credential a presented key authenticates as, or empty for anything else. */
  public Optional<SdkCredential> authenticate(String presentedKey) {
    if (!ApiKeyFormat.isWellFormed(presentedKey)) {
      return Optional.empty();
    }
    return Optional.ofNullable(byHash.get(ApiKeyFormat.hash(presentedKey)));
  }

  /**
   * Replaces the whole cache with the database's live keys, and announces every key it held that is
   * gone, so its streams end and its allowance is dropped here as on the instance that revoked it.
   *
   * <p>The lock is held while the database is read. An issue or revoke committing meanwhile waits
   * for it and is applied after, never overwritten by a snapshot taken before it committed.
   */
  public void reload() {
    List<SdkCredential> gone;
    synchronized (this) {
      Map<String, SdkCredential> live = new HashMap<>();
      for (ApiKeyEntity key : keys.findAllLive()) {
        live.put(
            key.getKeyHash(),
            new SdkCredential(key.id(), key.getEnvironmentId(), key.getKeyType()));
      }
      gone =
          byHash.entrySet().stream()
              .filter(held -> !live.containsKey(held.getKey()))
              .map(Map.Entry::getValue)
              .toList();
      byHash = Map.copyOf(live);
    }
    gone.forEach(credential -> events.publishEvent(new ApiKeyEvents.NoLongerLive(credential)));
  }

  /**
   * The backstop for a change announced through another instance and not heard: a lost
   * notification, or an instance not listening (ADR-041). With the database down, the cache keeps
   * the keys it has.
   */
  @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
  public void reconcile() {
    try {
      reload();
    } catch (RuntimeException e) {
      LOG.warn("Could not reload the API key cache; authenticating from the keys it holds", e);
    }
  }

  @TransactionalEventListener
  public void issued(ApiKeyEvents.Issued event) {
    synchronized (this) {
      Map<String, SdkCredential> next = new HashMap<>(byHash);
      next.put(event.keyHash(), event.credential());
      byHash = Map.copyOf(next);
    }
  }

  @TransactionalEventListener
  public void revoked(ApiKeyEvents.Revoked event) {
    synchronized (this) {
      Map<String, SdkCredential> next = new HashMap<>(byHash);
      next.remove(event.keyHash());
      byHash = Map.copyOf(next);
    }
  }

  int size() {
    return byHash.size();
  }
}
