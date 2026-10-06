package io.github.sanduniliyanage.flaglane.account.web;

import io.github.sanduniliyanage.flaglane.common.errors.TooManyRequestsException;
import io.github.sanduniliyanage.flaglane.common.security.TokenBuckets;
import io.github.sanduniliyanage.flaglane.common.security.TokenBuckets.Decision;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HexFormat;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Limits registration and sign-in per client address (ADR-029), before the body is read or a
 * password hashed. Each attempt costs a bcrypt hash whether it succeeds or not, so without a limit
 * one client could spend the server's CPU and guess passwords as fast as it can send them.
 *
 * <p>The address is the one the servlet container reports. Behind a reverse proxy that is the
 * proxy's, and every client would share one limit, unless the deployment has the container read the
 * proxy's forwarded headers.
 */
final class AuthRateLimitInterceptor implements HandlerInterceptor {

  private final TokenBuckets buckets;

  AuthRateLimitInterceptor(TokenBuckets buckets) {
    this.buckets = buckets;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    Decision decision = buckets.tryTake(clientKey(request.getRemoteAddr()));
    if (!decision.allowed()) {
      throw new TooManyRequestsException(
          "Too many sign-in and registration attempts from this address; try again shortly",
          decision.retryAfterSeconds());
    }
    return true;
  }

  /**
   * The key a client's bucket is kept under. An IPv6 client is usually given a whole /64, so every
   * address in one /64 shares a bucket; otherwise a single client could take a fresh bucket per
   * request. The address is parsed as a literal only, never looked up.
   */
  static String clientKey(String address) {
    if (address == null) {
      return "";
    }
    try {
      InetAddress parsed = InetAddress.ofLiteral(address);
      if (parsed instanceof Inet6Address) {
        return HexFormat.of().formatHex(parsed.getAddress(), 0, 8) + "/64";
      }
      return parsed.getHostAddress();
    } catch (IllegalArgumentException e) {
      return address;
    }
  }
}
