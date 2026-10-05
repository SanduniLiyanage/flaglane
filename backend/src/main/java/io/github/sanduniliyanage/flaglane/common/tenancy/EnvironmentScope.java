package io.github.sanduniliyanage.flaglane.common.tenancy;

import java.util.Objects;
import java.util.UUID;

/**
 * Proof that an environment exists inside a project the user owns. Only {@link TenantResolver}
 * creates one, by finding the environment within a {@link ProjectScope}.
 */
public final class EnvironmentScope {

  private final ProjectScope project;
  private final UUID environmentId;
  private final String environmentKey;

  EnvironmentScope(ProjectScope project, UUID environmentId, String environmentKey) {
    this.project = Objects.requireNonNull(project, "project");
    this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
    this.environmentKey = Objects.requireNonNull(environmentKey, "environmentKey");
  }

  public ProjectScope project() {
    return project;
  }

  public UUID projectId() {
    return project.projectId();
  }

  public UUID environmentId() {
    return environmentId;
  }

  public String environmentKey() {
    return environmentKey;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof EnvironmentScope scope
        && scope.environmentId.equals(environmentId)
        && scope.project.equals(project);
  }

  @Override
  public int hashCode() {
    return Objects.hash(project, environmentId);
  }

  @Override
  public String toString() {
    return "EnvironmentScope[" + project.projectKey() + "/" + environmentKey + "]";
  }
}
