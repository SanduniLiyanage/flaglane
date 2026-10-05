package io.github.sanduniliyanage.flaglane.serving.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sanduniliyanage.flaglane.evaluation.Ruleset;
import io.github.sanduniliyanage.flaglane.project.domain.RulesetChanged;
import io.github.sanduniliyanage.flaglane.serving.domain.RulesetSnapshot;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class RulesetCacheTest {

  private final RulesetLoader loader = mock(RulesetLoader.class);
  private final RulesetCache cache = new RulesetCache(loader);
  private final UUID production = UUID.randomUUID();

  @Test
  void isNotReadyUntilEveryEnvironmentHasLoadedOnce() {
    when(loader.versions()).thenReturn(Map.of(production, 3L));
    when(loader.load(production)).thenReturn(Optional.of(snapshot(production, 3)));

    assertThat(cache.isReady()).isFalse();
    cache.afterSingletonsInstantiated();

    assertThat(cache.isReady()).isTrue();
    assertThat(cache.get(production)).get().extracting(RulesetSnapshot::version).isEqualTo(3L);
  }

  @Test
  void aChangeAfterCommitSwapsInTheNewSnapshot() {
    when(loader.load(production))
        .thenReturn(Optional.of(snapshot(production, 1)), Optional.of(snapshot(production, 2)));
    cache.rebuild(production);

    cache.changed(new RulesetChanged(Set.of(production)));

    assertThat(cache.get(production)).get().extracting(RulesetSnapshot::version).isEqualTo(2L);
  }

  @Test
  void anOlderSnapshotNeverReplacesANewerOne() {
    when(loader.load(production))
        .thenReturn(Optional.of(snapshot(production, 5)), Optional.of(snapshot(production, 4)));
    cache.rebuild(production);

    cache.rebuild(production);

    assertThat(cache.get(production)).get().extracting(RulesetSnapshot::version).isEqualTo(5L);
  }

  @Test
  void aDeletedEnvironmentIsForgotten() {
    when(loader.load(production))
        .thenReturn(Optional.of(snapshot(production, 1)), Optional.empty());
    cache.rebuild(production);

    cache.changed(new RulesetChanged(Set.of(production)));

    assertThat(cache.get(production)).isEmpty();
  }

  @Test
  void aFailedRebuildKeepsServingTheLastSnapshot() {
    when(loader.load(production))
        .thenReturn(Optional.of(snapshot(production, 1)))
        .thenThrow(new DataAccessResourceFailureException("database down"));
    cache.rebuild(production);

    cache.changed(new RulesetChanged(Set.of(production)));

    assertThat(cache.get(production)).get().extracting(RulesetSnapshot::version).isEqualTo(1L);
  }

  @Test
  void reconciliationRebuildsWhatIsBehindAndDropsWhatIsGone() {
    UUID deleted = UUID.randomUUID();
    when(loader.load(production))
        .thenReturn(Optional.of(snapshot(production, 1)), Optional.of(snapshot(production, 2)));
    when(loader.load(deleted)).thenReturn(Optional.of(snapshot(deleted, 1)), Optional.empty());
    cache.rebuild(production);
    cache.rebuild(deleted);
    when(loader.versions()).thenReturn(Map.of(production, 2L));

    cache.reconcile();

    assertThat(cache.get(production)).get().extracting(RulesetSnapshot::version).isEqualTo(2L);
    assertThat(cache.get(deleted)).isEmpty();
  }

  @Test
  void reconciliationWithTheDatabaseDownChangesNothing() {
    when(loader.load(production)).thenReturn(Optional.of(snapshot(production, 1)));
    cache.rebuild(production);
    when(loader.versions()).thenThrow(new DataAccessResourceFailureException("database down"));

    cache.reconcile();

    assertThat(cache.get(production)).isPresent();
    verify(loader).versions();
  }

  private static RulesetSnapshot snapshot(UUID environment, long version) {
    return new RulesetSnapshot(
        environment,
        "production",
        version,
        Ruleset.empty("production", version),
        "{}",
        Ruleset.empty("production", version),
        "{}");
  }
}
