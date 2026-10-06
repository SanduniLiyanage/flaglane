package io.github.sanduniliyanage.flaglane.audit.domain;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;

/**
 * A position in the audit trail: the {@code (created_at, id)} of the last entry a reader has seen.
 * The next page holds the entries strictly before it in newest-first order, which is keyset
 * pagination (FR-AUD-003, E-025). An entry recorded while the reader pages lands before the first
 * page, so no page repeats or skips one.
 *
 * <p>Written as {@code <created_at>,<id>}, for example {@code
 * 2026-10-05T09:30:00.123456Z,0f6b3b5e-7c2a-4f5e-9d61-2a4b8f0e1c3d}.
 */
public record AuditCursor(Instant createdAt, UUID id) {

  public AuditCursor {
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(id, "id");
  }

  /**
   * Reads a cursor as {@link #toString()} writes it.
   *
   * @throws IllegalArgumentException if it is not one
   */
  public static AuditCursor of(String text) {
    int comma = text == null ? -1 : text.lastIndexOf(',');
    if (comma < 0) {
      throw new IllegalArgumentException("A cursor is <created_at>,<id>");
    }
    try {
      return new AuditCursor(
          Instant.parse(text.substring(0, comma)), UUID.fromString(text.substring(comma + 1)));
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("A cursor is <created_at>,<id>", e);
    }
  }

  @Override
  public String toString() {
    return createdAt + "," + id;
  }
}
