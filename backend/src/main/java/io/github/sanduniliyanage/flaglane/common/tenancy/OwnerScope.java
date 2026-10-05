package io.github.sanduniliyanage.flaglane.common.tenancy;

import java.util.Objects;
import java.util.UUID;

/**
 * The authenticated dashboard user, as the scope of what they may read and change. Only {@link
 * TenantContext} creates one, from a verified token.
 */
public final class OwnerScope {

  private final UUID userId;

  OwnerScope(UUID userId) {
    this.userId = Objects.requireNonNull(userId, "userId");
  }

  public UUID userId() {
    return userId;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof OwnerScope scope && scope.userId.equals(userId);
  }

  @Override
  public int hashCode() {
    return userId.hashCode();
  }

  @Override
  public String toString() {
    return "OwnerScope[" + userId + "]";
  }
}
