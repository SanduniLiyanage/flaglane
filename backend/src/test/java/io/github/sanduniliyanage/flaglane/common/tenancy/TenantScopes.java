package io.github.sanduniliyanage.flaglane.common.tenancy;

import java.util.UUID;

/**
 * Scopes for unit tests, which have no resolver to get them from. Lives in the scopes' own package
 * because their constructors are deliberately unreachable from anywhere else.
 */
public final class TenantScopes {

  private TenantScopes() {}

  public static OwnerScope owner(UUID userId) {
    return new OwnerScope(userId);
  }

  public static ProjectScope project(UUID projectId, String projectKey, UUID userId) {
    return new ProjectScope(projectId, projectKey, userId);
  }

  public static EnvironmentScope environment(
      ProjectScope project, UUID environmentId, String environmentKey) {
    return new EnvironmentScope(project, environmentId, environmentKey);
  }
}
