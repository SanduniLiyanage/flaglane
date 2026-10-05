package io.github.sanduniliyanage.flaglane.common.tenancy;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Who the current request is from, as the security filter established it. The dashboard's tenant is
 * the signed-in user, named by the token's subject.
 */
@Component
public class TenantContext {

  /**
   * @throws IllegalStateException outside a request authenticated by a dashboard token, which the
   *     filter chain makes unreachable from any {@code /api/**} endpoint that needs a tenant
   */
  public OwnerScope owner() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken token)) {
      throw new IllegalStateException("No dashboard user is authenticated for this request");
    }
    return new OwnerScope(UUID.fromString(token.getToken().getSubject()));
  }
}
