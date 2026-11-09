package io.github.sanduniliyanage.flaglane.cluster.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What one instance tells the others when a write commits (ADR-041): which instance made it, and
 * which environment's ruleset or which key changed. Never what the change was: every instance reads
 * that from the database, which is the only source of truth.
 *
 * <p>On the wire, a {@code NOTIFY} payload of four words: {@code flaglane1 <origin> ruleset <id>}
 * or {@code flaglane1 <origin> key <id>}. The first word versions the format, so a future one is
 * ignored by an older instance rather than misread.
 */
public record Change(UUID origin, Kind kind, UUID id) {

  /** The payload format this instance writes and reads. */
  static final String FORMAT = "flaglane1";

  /** What changed. */
  public enum Kind {
    /** An environment's ruleset: the id is the environment's. */
    RULESET,
    /** An API key was issued or revoked: the id is the key's. */
    KEY;

    String word() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public Change {
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(id, "id");
  }

  public String encode() {
    return FORMAT + " " + origin + " " + kind.word() + " " + id;
  }

  /** The change a payload describes, or empty for anything else, which is then ignored. */
  public static Optional<Change> decode(String payload) {
    if (payload == null) {
      return Optional.empty();
    }
    String[] words = payload.split(" ");
    if (words.length != 4 || !words[0].equals(FORMAT)) {
      return Optional.empty();
    }
    try {
      UUID origin = UUID.fromString(words[1]);
      UUID id = UUID.fromString(words[3]);
      for (Kind kind : Kind.values()) {
        if (kind.word().equals(words[2])) {
          return Optional.of(new Change(origin, kind, id));
        }
      }
      return Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
