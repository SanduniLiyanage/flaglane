package io.github.sanduniliyanage.flaglane.audit.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** One page of the audit trail. */
public record AuditPageResponse(
    @Schema(description = "Newest first") List<AuditEntryResponse> entries,
    @Schema(
            description = "Pass as `before` for the next page; null when this page is the last",
            example = "2026-10-05T09:30:00.123456Z,0f6b3b5e-7c2a-4f5e-9d61-2a4b8f0e1c3d",
            nullable = true)
        String next) {

  public AuditPageResponse {
    entries = List.copyOf(entries);
  }
}
