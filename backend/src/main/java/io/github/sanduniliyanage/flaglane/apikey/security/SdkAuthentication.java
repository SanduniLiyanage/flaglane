package io.github.sanduniliyanage.flaglane.apikey.security;

import io.github.sanduniliyanage.flaglane.apikey.domain.SdkCredential;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * An {@code /sdk/**} request authenticated by an API key: the environment it may read and the key
 * type that decides how much of it (FR-KEY-004, FR-KEY-005). The key itself is not kept.
 */
public final class SdkAuthentication extends AbstractAuthenticationToken {

  private static final long serialVersionUID = 1L;

  private final transient SdkCredential credential;

  public SdkAuthentication(SdkCredential credential) {
    super(List.of());
    this.credential = credential;
    setAuthenticated(true);
  }

  public SdkCredential credential() {
    return credential;
  }

  @Override
  public Object getCredentials() {
    return "";
  }

  @Override
  public Object getPrincipal() {
    return credential;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof SdkAuthentication authentication
        && authentication.credential.equals(credential);
  }

  @Override
  public int hashCode() {
    return credential.hashCode();
  }
}
