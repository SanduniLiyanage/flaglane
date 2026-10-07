package io.github.sanduniliyanage.flaglane.serving.web;

import io.github.sanduniliyanage.flaglane.apikey.domain.SdkCredential;
import io.github.sanduniliyanage.flaglane.common.errors.TooManyRequestsException;
import io.github.sanduniliyanage.flaglane.common.security.TokenBuckets.Decision;
import io.github.sanduniliyanage.flaglane.serving.service.RulesetCache;
import io.github.sanduniliyanage.flaglane.serving.service.SdkRateLimit;
import io.github.sanduniliyanage.flaglane.serving.service.SdkRateLimit.Cost;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Charges each {@code /sdk/**} request to its key's allowance (NFR-SEC-005, ADR-035), before the
 * handler runs: a refused request reads no body and evaluates nothing. Beyond the allowance the
 * answer is 429 with {@code Retry-After}, which the SDK waits out on its last ruleset.
 *
 * <p>The key is the one the {@code /sdk/**} chain authenticated. A request without one never gets
 * here, since that chain answers it with a 401 first; an unknown key costs a hash and a map lookup
 * and is not limited by key, there being no key to limit.
 */
final class SdkRateLimitInterceptor implements HandlerInterceptor {

  private final SdkRateLimit limit;
  private final RulesetCache cache;

  SdkRateLimitInterceptor(SdkRateLimit limit, RulesetCache cache) {
    this.limit = limit;
    this.cache = cache;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof SdkCredential key)) {
      return true;
    }
    Decision decision = limit.charge(key.keyId(), cost(request, key));
    if (!decision.allowed()) {
      throw new TooManyRequestsException(
          "Too many requests with this API key; try again shortly", decision.retryAfterSeconds());
    }
    return true;
  }

  /**
   * A request that will be answered {@code 304} costs a tenth of one that will not. That is read
   * from the cache the controller answers from; a ruleset that changes between the two is charged
   * as what it was a moment earlier, once.
   */
  private Cost cost(HttpServletRequest request, SdkCredential key) {
    String ifNoneMatch = request.getHeader(HttpHeaders.IF_NONE_MATCH);
    if (ifNoneMatch == null || !HttpMethod.GET.matches(request.getMethod())) {
      return Cost.FULL;
    }
    boolean unchanged =
        cache
            .get(key.environmentId())
            .map(snapshot -> SdkController.matches(ifNoneMatch, snapshot.etag(key.keyType())))
            .orElse(false);
    return unchanged ? Cost.NOT_MODIFIED : Cost.FULL;
  }
}
