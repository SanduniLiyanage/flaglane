package io.github.sanduniliyanage.flaglane.streaming.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKeyEvents;
import io.github.sanduniliyanage.flaglane.apikey.domain.KeyType;
import io.github.sanduniliyanage.flaglane.apikey.domain.SdkCredential;
import io.github.sanduniliyanage.flaglane.common.errors.TooManyRequestsException;
import io.github.sanduniliyanage.flaglane.serving.service.RulesetCache;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The registry's bookkeeping, without a network: the ceilings per key and per environment, places
 * given back, and no stream after shutdown (FR-STR-003, FR-STR-004). What reaches a client is
 * {@code StreamEndToEndTest}'s.
 */
class StreamRegistryTest {

  private final RulesetCache cache = mock(RulesetCache.class);
  private final StreamRegistry registry =
      new StreamRegistry(
          new StreamProperties(2, 3, Duration.ofSeconds(25), Duration.ofMinutes(30)), cache);
  private final UUID production = UUID.randomUUID();

  StreamRegistryTest() {
    when(cache.get(production)).thenReturn(Optional.empty());
    registry.start();
  }

  @AfterEach
  void shutDown() {
    registry.stop();
    registry.destroy();
  }

  @Test
  void aKeyMayHoldTwoStreamsAndNoMore() {
    SdkCredential key = key(production);
    registry.open(key);
    registry.open(key);

    assertThatThrownBy(() -> registry.open(key))
        .isInstanceOf(TooManyRequestsException.class)
        .hasMessageContaining("this API key");
    assertThat(registry.size()).isEqualTo(2);
  }

  @Test
  void anEnvironmentMayHoldThreeStreamsWhicheverKeysHoldThem() {
    registry.open(key(production));
    registry.open(key(production));
    registry.open(key(production));

    assertThatThrownBy(() -> registry.open(key(production)))
        .isInstanceOf(TooManyRequestsException.class)
        .hasMessageContaining("this environment");
    assertThat(registry.open(key(UUID.randomUUID()))).as("another environment").isPresent();
  }

  @Test
  void revokingAKeyGivesItsPlacesBack() {
    SdkCredential key = key(production);
    registry.open(key);
    registry.open(key);

    registry.revoked(new ApiKeyEvents.Revoked("hash", key));

    assertThat(registry.size()).isZero();
    assertThat(registry.open(key)).isPresent();
  }

  @Test
  void revokingOneKeyLeavesAnotherKeysStreamsOpen() {
    SdkCredential revoked = key(production);
    SdkCredential kept = key(production);
    registry.open(revoked);
    registry.open(kept);

    registry.revoked(new ApiKeyEvents.Revoked("hash", revoked));

    assertThat(registry.size()).isEqualTo(1);
  }

  @Test
  void aKeyRevokedThroughAnotherInstanceGivesItsPlacesBackToo() {
    SdkCredential key = key(production);
    registry.open(key);
    registry.open(key);

    registry.noLongerLive(new ApiKeyEvents.NoLongerLive(key));

    assertThat(registry.size()).isZero();
    assertThat(registry.open(key)).isPresent();
  }

  @Test
  void afterShutdownBeginsNoStreamIsOpenedAndNoneIsHeld() {
    registry.open(key(production));

    registry.stop();

    assertThat(registry.isRunning()).isFalse();
    assertThat(registry.size()).isZero();
    assertThat(registry.open(key(production))).isEmpty();
  }

  private static SdkCredential key(UUID environment) {
    return new SdkCredential(UUID.randomUUID(), environment, KeyType.SERVER);
  }
}
