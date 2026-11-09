package io.github.sanduniliyanage.flaglane.cluster.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What one instance tells the others, on the wire (ADR-041). */
class ChangeTest {

  @Test
  void aChangeReadsBackAsItWasWritten() {
    Change ruleset = new Change(UUID.randomUUID(), Change.Kind.RULESET, UUID.randomUUID());
    Change key = new Change(UUID.randomUUID(), Change.Kind.KEY, UUID.randomUUID());

    assertThat(Change.decode(ruleset.encode())).contains(ruleset);
    assertThat(Change.decode(key.encode())).contains(key);
  }

  @Test
  void thePayloadNamesWhatChangedAndNothingOfTheChange() {
    UUID origin = UUID.fromString("3f2c0000-0000-0000-0000-000000000001");
    UUID environment = UUID.fromString("9a1b0000-0000-0000-0000-000000000002");

    assertThat(new Change(origin, Change.Kind.RULESET, environment).encode())
        .isEqualTo("flaglane1 " + origin + " ruleset " + environment);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "flaglane1",
        "flaglane2 3f2c0000-0000-0000-0000-000000000001 ruleset 9a1b0000-0000-0000-0000-000000000002",
        "flaglane1 not-a-uuid ruleset 9a1b0000-0000-0000-0000-000000000002",
        "flaglane1 3f2c0000-0000-0000-0000-000000000001 flags 9a1b0000-0000-0000-0000-000000000002",
        "flaglane1 3f2c0000-0000-0000-0000-000000000001 ruleset 9a1b0000-0000-0000-0000-000000000002 x"
      })
  void anythingElseIsIgnoredRatherThanMisread(String payload) {
    assertThat(Change.decode(payload)).isEmpty();
  }

  @Test
  void noPayloadIsNothing() {
    assertThat(Change.decode(null)).isEmpty();
  }
}
