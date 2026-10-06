package io.github.sanduniliyanage.flaglane.audit.web;

import io.github.sanduniliyanage.flaglane.audit.domain.AuditCursor;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditEntry;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditPage;

final class AuditMapper {

  private AuditMapper() {}

  static AuditPageResponse toResponse(AuditPage page) {
    return new AuditPageResponse(
        page.entries().stream().map(AuditMapper::toResponse).toList(),
        page.next().map(AuditCursor::toString).orElse(null));
  }

  static AuditEntryResponse toResponse(AuditEntry entry) {
    return new AuditEntryResponse(
        entry.id(),
        entry.createdAt(),
        entry.action(),
        new AuditEntryResponse.Actor(entry.actorEmail(), entry.actorDisplayName()),
        entry.environmentKey(),
        entry.environmentDeleted(),
        entry.flagKey(),
        entry.previousValue(),
        entry.newValue());
  }
}
