package io.github.sanduniliyanage.flaglane.common.security;

import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties.ForwardHeadersStrategy;
import org.springframework.context.annotation.Configuration;

/**
 * Whose address and scheme a request has, behind a reverse proxy (ADR-037).
 *
 * <p>{@code FLAGLANE_FORWARDED_HEADERS} is {@code none} by default, and the client is the peer that
 * opened the connection. Set to {@code native}, Tomcat's {@code RemoteIpValve} reads {@code
 * X-Forwarded-For} and {@code X-Forwarded-Proto} from a peer on a private address only, and takes
 * as the client the rightmost forwarded address that is not itself a proxy's, so a client cannot
 * name its own address by sending the header: the proxy appends the address it saw.
 *
 * <p>{@code framework} is refused at startup. Spring's {@code ForwardedHeaderFilter} believes the
 * headers from any peer, so every client could take a new sign-in allowance (ADR-029) on every
 * request by naming a new address in them.
 */
@Configuration(proxyBeanMethods = false)
public final class ForwardedHeadersConfiguration {

  public ForwardedHeadersConfiguration(ServerProperties server) {
    requireProxiesToBeTrustedByAddress(server.getForwardHeadersStrategy());
  }

  static void requireProxiesToBeTrustedByAddress(ForwardHeadersStrategy strategy) {
    if (strategy == ForwardHeadersStrategy.FRAMEWORK) {
      throw new IllegalStateException(
          "FLAGLANE_FORWARDED_HEADERS must be none or native, not framework: framework believes"
              + " X-Forwarded-For from any client, so each client could name its own address"
              + " (ADR-037)");
    }
  }
}
