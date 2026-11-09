package io.github.sanduniliyanage.flaglane.cluster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.sanduniliyanage.flaglane.apikey.service.ApiKeyCache;
import io.github.sanduniliyanage.flaglane.cluster.domain.Change;
import io.github.sanduniliyanage.flaglane.cluster.domain.InstanceId;
import io.github.sanduniliyanage.flaglane.serving.service.RulesetCache;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * What an instance does with an announcement (ADR-041). Hearing one over a real connection is
 * {@code InstancesEndToEndTest}'s.
 */
class ChangeListenerTest {

  private final InstanceId self = new InstanceId(UUID.randomUUID());
  private final UUID other = UUID.randomUUID();
  private final RulesetCache rulesets = mock(RulesetCache.class);
  private final ApiKeyCache keys = mock(ApiKeyCache.class);
  private final ChangeListener listener =
      new ChangeListener(
          new InstanceSyncProperties(true, "jdbc:postgresql://nowhere/x", "u", "p"),
          self,
          rulesets,
          keys);

  @Test
  void anEnvironmentChangedElsewhereIsRebuiltHere() {
    UUID environment = UUID.randomUUID();

    listener.handle(new Change(other, Change.Kind.RULESET, environment).encode());

    verify(rulesets).rebuild(environment);
    verifyNoInteractions(keys);
  }

  @Test
  void aKeyChangedElsewhereReloadsTheKeys() {
    listener.handle(new Change(other, Change.Kind.KEY, UUID.randomUUID()).encode());

    verify(keys).reload();
    verifyNoInteractions(rulesets);
  }

  @Test
  void itsOwnAnnouncementsArePassedOver() {
    listener.handle(new Change(self.value(), Change.Kind.RULESET, UUID.randomUUID()).encode());
    listener.handle(new Change(self.value(), Change.Kind.KEY, UUID.randomUUID()).encode());

    verifyNoInteractions(rulesets, keys);
  }

  @Test
  void anAnnouncementItCannotReadChangesNothing() {
    listener.handle("something else entirely");

    verifyNoInteractions(rulesets, keys);
  }

  @Test
  void aChangeItCannotApplyNowNeverEscapes() {
    UUID environment = UUID.randomUUID();
    doThrow(new DataAccessResourceFailureException("down")).when(rulesets).rebuild(environment);

    assertThatCode(
            () -> listener.handle(new Change(other, Change.Kind.RULESET, environment).encode()))
        .doesNotThrowAnyException();
  }

  @Test
  void retriesBackOffFromOneSecondToThirtyWithJitter() {
    assertThat(ChangeListener.retryAfter(1)).isBetween(ms(800), ms(1_200));
    assertThat(ChangeListener.retryAfter(3)).isBetween(ms(3_200), ms(4_800));
    assertThat(ChangeListener.retryAfter(50)).isBetween(ms(25_600), ms(30_000));
  }

  private static Duration ms(long millis) {
    return Duration.ofMillis(millis);
  }
}
