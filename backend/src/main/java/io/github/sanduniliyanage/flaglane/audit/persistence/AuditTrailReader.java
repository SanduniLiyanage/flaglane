package io.github.sanduniliyanage.flaglane.audit.persistence;

import io.github.sanduniliyanage.flaglane.audit.domain.AuditCursor;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the audit trail of one project, newest first, a page at a time (FR-AUD-003). One query per
 * page, whatever is on it: the actor, environment and flag are joined rather than looked up per
 * entry.
 *
 * <p>Pagination is keyset on {@code (created_at, id)}, compared as a row so that PostgreSQL walks
 * {@code ix_audit_entries_project_created} from the cursor rather than counting past an offset.
 * Every query is bounded by the project the scope proves the caller owns (NFR-SEC-004).
 */
@Repository
public class AuditTrailReader {

  private static final String SELECT =
      """
      select a.id, a.created_at, a.action,
             u.email as actor_email, u.display_name as actor_display_name,
             coalesce(e.key, (select d.previous_value ->> 'key'
                                from audit_entries d
                               where d.environment_id = a.environment_id
                                 and d.action = 'environment.deleted'
                                 and d.project_id = a.project_id
                               limit 1)) as environment_key,
             (a.environment_id is not null and e.id is null) as environment_deleted,
             f.key as flag_key,
             a.previous_value::text as previous_value,
             a.new_value::text as new_value
        from audit_entries a
        join users u on u.id = a.actor_id
        left join environments e on e.id = a.environment_id and e.project_id = a.project_id
        left join flags f on f.id = a.flag_id and f.project_id = a.project_id
       where a.project_id = :project
      """;

  private final NamedParameterJdbcTemplate database;

  public AuditTrailReader(NamedParameterJdbcTemplate database) {
    this.database = database;
  }

  /**
   * Up to {@code limit} entries before {@code before}, or from the newest if it is null, narrowed
   * to one environment and one flag where they are given.
   *
   * @param environment only entries about this environment, or null for every entry
   * @param flagKey only entries about the project's flag with this key, or null for every entry
   */
  public List<AuditTrailRow> page(
      ProjectScope project,
      EnvironmentScope environment,
      String flagKey,
      AuditCursor before,
      int limit) {
    StringBuilder sql = new StringBuilder(SELECT);
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("project", project.projectId())
            .addValue("limit", limit);
    if (environment != null) {
      sql.append(" and a.environment_id = :environment");
      parameters.addValue("environment", environment.environmentId());
    }
    if (flagKey != null) {
      sql.append(
          " and a.flag_id = (select id from flags where project_id = :project and key = :flag)");
      parameters.addValue("flag", flagKey);
    }
    if (before != null) {
      sql.append(" and (a.created_at, a.id) < (:beforeAt, :beforeId)");
      parameters
          .addValue("beforeAt", OffsetDateTime.ofInstant(before.createdAt(), ZoneOffset.UTC))
          .addValue("beforeId", before.id());
    }
    sql.append(" order by a.created_at desc, a.id desc limit :limit");
    return database.query(sql.toString(), parameters, (row, index) -> read(row));
  }

  private static AuditTrailRow read(ResultSet row) throws SQLException {
    return new AuditTrailRow(
        row.getObject("id", UUID.class),
        row.getObject("created_at", OffsetDateTime.class).toInstant(),
        row.getString("action"),
        row.getString("actor_email"),
        row.getString("actor_display_name"),
        row.getString("environment_key"),
        row.getBoolean("environment_deleted"),
        row.getString("flag_key"),
        row.getString("previous_value"),
        row.getString("new_value"));
  }
}
