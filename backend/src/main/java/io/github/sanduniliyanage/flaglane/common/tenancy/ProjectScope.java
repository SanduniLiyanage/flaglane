package io.github.sanduniliyanage.flaglane.common.tenancy;

import java.util.Objects;
import java.util.UUID;

/**
 * Proof that a project exists and belongs to the user it was resolved for. Only {@link
 * TenantResolver} creates one, and only by finding the project among that user's own.
 *
 * <p>Tenant-scoped repository methods take a scope rather than an id, so a query cannot be written
 * that forgets which project it is about: the only project id a service can hold in this form is
 * one the caller owns (NFR-SEC-004).
 */
public final class ProjectScope {

  private final UUID projectId;
  private final String projectKey;
  private final UUID userId;

  ProjectScope(UUID projectId, String projectKey, UUID userId) {
    this.projectId = Objects.requireNonNull(projectId, "projectId");
    this.projectKey = Objects.requireNonNull(projectKey, "projectKey");
    this.userId = Objects.requireNonNull(userId, "userId");
  }

  public UUID projectId() {
    return projectId;
  }

  public String projectKey() {
    return projectKey;
  }

  /** The authenticated user this scope was resolved for: the actor of anything done through it. */
  public UUID userId() {
    return userId;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ProjectScope scope
        && scope.projectId.equals(projectId)
        && scope.userId.equals(userId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(projectId, userId);
  }

  @Override
  public String toString() {
    return "ProjectScope[" + projectKey + "]";
  }
}
