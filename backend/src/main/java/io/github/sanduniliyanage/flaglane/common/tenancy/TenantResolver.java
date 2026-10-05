package io.github.sanduniliyanage.flaglane.common.tenancy;

import io.github.sanduniliyanage.flaglane.common.errors.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The one place a {@link ProjectScope} or {@link EnvironmentScope} comes from. Each is resolved by
 * a query that carries the scope above it, so an environment is only ever found inside a project,
 * and a project only among the user's own.
 *
 * <p>A project that exists but belongs to someone else is not found, with the same message as one
 * that does not exist at all (FR-PRJ-003).
 */
@Component
public class TenantResolver {

  static final String PROJECT_NOT_FOUND = "Project not found";
  static final String ENVIRONMENT_NOT_FOUND = "Environment not found";

  private final JdbcTemplate database;

  public TenantResolver(JdbcTemplate database) {
    this.database = database;
  }

  public ProjectScope project(OwnerScope owner, String projectKey) {
    List<UUID> ids =
        database.queryForList(
            "select id from projects where owner_id = ? and key = ?",
            UUID.class,
            owner.userId(),
            projectKey);
    if (ids.isEmpty()) {
      throw new NotFoundException(PROJECT_NOT_FOUND);
    }
    return new ProjectScope(ids.getFirst(), projectKey, owner.userId());
  }

  /** Every environment of the project, as scopes, in creation order. */
  public List<EnvironmentScope> environments(ProjectScope project) {
    return database.query(
        "select id, key from environments where project_id = ? order by created_at, key",
        (row, index) ->
            new EnvironmentScope(project, row.getObject("id", UUID.class), row.getString("key")),
        project.projectId());
  }

  public EnvironmentScope environment(ProjectScope project, String environmentKey) {
    List<UUID> ids =
        database.queryForList(
            "select id from environments where project_id = ? and key = ?",
            UUID.class,
            project.projectId(),
            environmentKey);
    if (ids.isEmpty()) {
      throw new NotFoundException(ENVIRONMENT_NOT_FOUND);
    }
    return new EnvironmentScope(project, ids.getFirst(), environmentKey);
  }
}
