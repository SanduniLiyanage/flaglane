package io.github.sanduniliyanage.flaglane.apikey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKeyEvents;
import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKeyFormat;
import io.github.sanduniliyanage.flaglane.apikey.domain.KeyType;
import io.github.sanduniliyanage.flaglane.apikey.domain.SdkCredential;
import io.github.sanduniliyanage.flaglane.apikey.persistence.ApiKeyEntity;
import io.github.sanduniliyanage.flaglane.apikey.persistence.ApiKeyRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class ApiKeyCacheTest {

  private final ApiKeyRepository keys = mock(ApiKeyRepository.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final ApiKeyCache cache = new ApiKeyCache(keys, events);
  private final String serverKey = ApiKeyFormat.generate(KeyType.SERVER, new SecureRandom());
  private final UUID environment = UUID.randomUUID();

  @Test
  void aLiveKeyLoadedAtStartupAuthenticatesAsItsEnvironmentAndType() {
    ApiKeyEntity live = entity(serverKey, KeyType.SERVER);
    when(keys.findAllLive()).thenReturn(List.of(live));

    cache.afterSingletonsInstantiated();

    assertThat(cache.authenticate(serverKey))
        .contains(new SdkCredential(live.id(), environment, KeyType.SERVER));
  }

  @Test
  void theTypeComesFromTheDatabaseNotFromTheMarker() {
    // A row saying client for a key whose string says server: the row wins.
    ApiKeyEntity row = entity(serverKey, KeyType.CLIENT);
    when(keys.findAllLive()).thenReturn(List.of(row));
    cache.reload();

    assertThat(cache.authenticate(serverKey))
        .get()
        .extracting(SdkCredential::keyType)
        .isEqualTo(KeyType.CLIENT);
  }

  @Test
  void anUnknownOrMalformedKeyAuthenticatesAsNothing() {
    when(keys.findAllLive()).thenReturn(List.of());
    cache.reload();

    assertThat(cache.authenticate(serverKey)).isEmpty();
    assertThat(cache.authenticate("flg_srv_short")).isEmpty();
    assertThat(cache.authenticate(null)).isEmpty();
  }

  @Test
  void anIssuedKeyAuthenticatesOnceItsTransactionHasCommitted() {
    SdkCredential credential = new SdkCredential(UUID.randomUUID(), environment, KeyType.SERVER);

    cache.issued(new ApiKeyEvents.Issued(ApiKeyFormat.hash(serverKey), credential));

    assertThat(cache.authenticate(serverKey)).contains(credential);
  }

  @Test
  void aRevokedKeyStopsAuthenticating() {
    SdkCredential credential = new SdkCredential(UUID.randomUUID(), environment, KeyType.SERVER);
    cache.issued(new ApiKeyEvents.Issued(ApiKeyFormat.hash(serverKey), credential));

    cache.revoked(new ApiKeyEvents.Revoked(ApiKeyFormat.hash(serverKey), credential));

    assertThat(cache.authenticate(serverKey)).isEmpty();
    assertThat(cache.size()).isZero();
  }

  @Test
  void aReloadAnnouncesEachKeyItNoLongerHoldsAndNoOther() {
    String revokedElsewhere = ApiKeyFormat.generate(KeyType.SERVER, new SecureRandom());
    ApiKeyEntity kept = entity(serverKey, KeyType.SERVER);
    ApiKeyEntity gone = entity(revokedElsewhere, KeyType.SERVER);
    when(keys.findAllLive()).thenReturn(List.of(kept, gone), List.of(kept));
    cache.reload();

    cache.reload();

    assertThat(cache.authenticate(revokedElsewhere)).isEmpty();
    assertThat(cache.authenticate(serverKey)).isPresent();
    verify(events)
        .publishEvent(
            new ApiKeyEvents.NoLongerLive(
                new SdkCredential(gone.id(), environment, KeyType.SERVER)));
    verify(events, never())
        .publishEvent(
            new ApiKeyEvents.NoLongerLive(
                new SdkCredential(kept.id(), environment, KeyType.SERVER)));
  }

  @Test
  void aKeyRevokedHereIsNotAnnouncedAgainByTheNextReload() {
    ApiKeyEntity row = entity(serverKey, KeyType.SERVER);
    when(keys.findAllLive()).thenReturn(List.of(row), List.of());
    cache.reload();
    SdkCredential credential = new SdkCredential(row.id(), environment, KeyType.SERVER);
    cache.revoked(new ApiKeyEvents.Revoked(ApiKeyFormat.hash(serverKey), credential));

    cache.reload();

    verifyNoInteractions(events);
  }

  @Test
  void aReconciliationWithTheDatabaseDownKeepsTheKeys() {
    when(keys.findAllLive())
        .thenReturn(List.of(entity(serverKey, KeyType.SERVER)))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
    cache.reload();

    cache.reconcile();

    assertThat(cache.authenticate(serverKey)).isPresent();
  }

  @Test
  void authenticatingNeverTouchesTheRepository() {
    cache.authenticate(serverKey);

    verify(keys, never()).findAllLive();
  }

  private ApiKeyEntity entity(String key, KeyType recordedType) {
    return new ApiKeyEntity(
        UUID.randomUUID(),
        environment,
        ApiKeyFormat.hash(key),
        ApiKeyFormat.prefix(key),
        recordedType,
        "k",
        Instant.parse("2026-10-05T09:30:00Z"));
  }
}
