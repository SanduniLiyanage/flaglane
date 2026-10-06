package io.github.sanduniliyanage.flaglane.audit.domain;

import java.util.List;
import java.util.Optional;

/**
 * Up to one page of the audit trail, newest first, and where the next page starts if there is one.
 */
public record AuditPage(List<AuditEntry> entries, Optional<AuditCursor> next) {

  public AuditPage {
    entries = List.copyOf(entries);
  }
}
