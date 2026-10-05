package io.github.sanduniliyanage.flaglane.apikey.service;

import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKeyEvents;
import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKeyFormat;
import io.github.sanduniliyanage.flaglane.apikey.domain.SdkCredential;
import io.github.sanduniliyanage.flaglane.apikey.persistence.ApiKeyEntity;
import io.github.sanduniliyanage.flaglane.apikey.persistence.ApiKeyRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Every live key's hash, in memory, so that authenticating an SDK request touches no database
 * (FR-KEY-007, NFR-PER-004). With the database down, serving carries on authenticating the keys it
 * knew; "rejected immediately" on revoke is a property of the invalidation below, not of a lookup
 * per request.
 *
 * <p>Loaded before the application accepts requests, and changed only after the transaction that
 * issued or revoked a key commits. Reads are lock-free; each write replaces the map, which costs a
 * copy per issue or revoke and nothing per request.
 */
@Component
public class ApiKeyCache implements SmartInitializingSingleton {

  private final ApiKeyRepository keys;
  private volatile Map<String, SdkCredential> byHash = Map.of();

  public ApiKeyCache(ApiKeyRepository keys) {
    this.keys = keys;
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

  /** Replaces the whole cache with the database's live keys. */
  public void reload() {
    Map<String, SdkCredential> live = new HashMap<>();
    for (ApiKeyEntity key : keys.findAllLive()) {
      live.put(
          key.getKeyHash(), new SdkCredential(key.id(), key.getEnvironmentId(), key.getKeyType()));
    }
    byHash = Map.copyOf(live);
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
