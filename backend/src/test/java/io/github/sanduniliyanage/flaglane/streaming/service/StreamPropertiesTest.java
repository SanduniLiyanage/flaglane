package io.github.sanduniliyanage.flaglane.streaming.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A ceiling is a number, and a heartbeat comes at least every 30 seconds (FR-STR-002, FR-STR-004).
 */
class StreamPropertiesTest {

  private static final Duration HEARTBEAT = Duration.ofSeconds(25);
  private static final Duration LIFETIME = Duration.ofMinutes(30);

  @Test
  void theDefaultsAreAccepted() {
    assertThatCode(() -> new StreamProperties(500, 1000, HEARTBEAT, LIFETIME))
        .doesNotThrowAnyException();
  }

  @Test
  void aCeilingIsAtLeastOne() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StreamProperties(0, 1000, HEARTBEAT, LIFETIME))
        .withMessageContaining("FLAGLANE_STREAM_MAX_PER_KEY");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StreamProperties(500, 0, HEARTBEAT, LIFETIME))
        .withMessageContaining("FLAGLANE_STREAM_MAX_PER_ENVIRONMENT");
  }

  @Test
  void aHeartbeatLongerThanThirtySecondsIsRefused() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StreamProperties(500, 1000, Duration.ofSeconds(31), LIFETIME))
        .withMessageContaining("at most 30 seconds");
  }

  @Test
  void aStreamOutlivesItsHeartbeat() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StreamProperties(500, 1000, HEARTBEAT, HEARTBEAT))
        .withMessageContaining("longer than the heartbeat");
  }
}
