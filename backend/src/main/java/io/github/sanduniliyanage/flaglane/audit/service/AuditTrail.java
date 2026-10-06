package io.github.sanduniliyanage.flaglane.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditCursor;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditEntry;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditPage;
import io.github.sanduniliyanage.flaglane.audit.persistence.AuditTrailReader;
import io.github.sanduniliyanage.flaglane.audit.persistence.AuditTrailRow;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a project's audit trail a page at a time, newest first (FR-AUD-003). It is the read side of
 * {@link AuditLog}, and changes nothing.
 */
@Service
public class AuditTrail {

  /** Entries per page when the caller does not say. */
  public static final int DEFAULT_LIMIT = 50;

  /** The most entries one page may hold. */
  public static final int MAX_LIMIT = 100;

  private static final TypeReference<Map<String, Object>> STATE = new TypeReference<>() {};

  private final AuditTrailReader reader;
  private final TenantResolver tenants;
  private final ObjectMapper json;

  public AuditTrail(AuditTrailReader reader, TenantResolver tenants, ObjectMapper json) {
    this.reader = reader;
    this.tenants = tenants;
    this.json = json;
  }

  /**
   * One page of the project's trail.
   *
   * @param environmentKey only entries about this environment of the project, or null for all
   * @param flagKey only entries about this flag of the project, archived or not, or null for all
   * @param before where the previous page ended, or null to start from the newest entry
   * @throws io.github.sanduniliyanage.flaglane.common.errors.NotFoundException if the project has
   *     no such environment or flag
   */
  @Transactional(readOnly = true)
  public AuditPage page(
      ProjectScope project, String environmentKey, String flagKey, AuditCursor before, int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be from 1 to " + MAX_LIMIT);
    }
    EnvironmentScope environment =
        environmentKey == null ? null : tenants.environment(project, environmentKey);
    if (flagKey != null) {
      tenants.requireFlag(project, flagKey);
    }
    // One more than asked for says whether there is a next page without a second query.
    List<AuditTrailRow> rows = reader.page(project, environment, flagKey, before, limit + 1);
    List<AuditEntry> entries = rows.stream().limit(limit).map(this::toEntry).toList();
    Optional<AuditCursor> next =
        rows.size() > limit ? Optional.of(entries.getLast().cursor()) : Optional.empty();
    return new AuditPage(entries, next);
  }

  private AuditEntry toEntry(AuditTrailRow row) {
    return new AuditEntry(
        row.id(),
        row.createdAt(),
        row.action(),
        row.actorEmail(),
        row.actorDisplayName(),
        row.environmentKey(),
        row.environmentDeleted(),
        row.flagKey(),
        state(row.previousValueJson()),
        state(row.newValueJson()));
  }

  private Map<String, Object> state(String recorded) {
    if (recorded == null) {
      return null;
    }
    try {
      return json.readValue(recorded, STATE);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException("An audit entry holds a state that is not a JSON object", e);
    }
  }
}
