package io.github.sanduniliyanage.flaglane.apikey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sanduniliyanage.flaglane.apikey.persistence.ApiKeyRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** FR-KEY-006: last use recorded off the request path, at most one write per key per flush. */
class LastUsedRecorderTest {

  private final ApiKeyRepository keys = mock(ApiKeyRepository.class);
  private final MovableClock clock = new MovableClock(Instant.parse("2026-10-05T09:30:00Z"));
  private final LastUsedRecorder recorder =
      new LastUsedRecorder(keys, new ImmediateTransactions(), clock);

  @Test
  void recordingAUseWritesNothing() {
    recorder.record(UUID.randomUUID());

    verify(keys, never()).markUsed(any(), any());
  }

  @Test
  void manyUsesOfOneKeyAreOneWriteOfTheLatest() {
    UUID key = UUID.randomUUID();
    for (int i = 0; i < 10_000; i++) {
      recorder.record(key);
      clock.advance(Duration.ofMillis(5));
    }

    recorder.flush();

    verify(keys, times(1)).markUsed(key, clock.instant().minusMillis(5));
    assertThat(recorder.pendingKeys()).isZero();
  }

  @Test
  void aFlushThatCannotReachTheDatabaseKeepsTheUseForTheNextOne() {
    UUID key = UUID.randomUUID();
    recorder.record(key);
    when(keys.markUsed(any(), any()))
        .thenThrow(new DataAccessResourceFailureException("database down"))
        .thenReturn(1);

    recorder.flush();
    recorder.flush();

    verify(keys, times(2)).markUsed(any(), any());
    assertThat(recorder.pendingKeys()).isZero();
  }

  @Test
  void aFlushWithNothingRecordedWritesNothing() {
    recorder.flush();

    verify(keys, never()).markUsed(any(), any());
  }

  /** Runs each callback directly; the repository is a mock, so there is nothing to commit. */
  private static final class ImmediateTransactions extends TransactionTemplate {

    private static final long serialVersionUID = 1L;

    @Override
    public <T> T execute(TransactionCallback<T> action) {
      return action.doInTransaction(new SimpleTransactionStatus());
    }
  }

  private static final class MovableClock extends Clock {

    private Instant now;

    MovableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
