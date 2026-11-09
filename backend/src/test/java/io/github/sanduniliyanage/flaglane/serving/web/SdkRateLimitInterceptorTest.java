package io.github.sanduniliyanage.flaglane.serving.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sanduniliyanage.flaglane.apikey.domain.KeyType;
import io.github.sanduniliyanage.flaglane.apikey.domain.SdkCredential;
import io.github.sanduniliyanage.flaglane.common.security.TokenBuckets.Decision;
import io.github.sanduniliyanage.flaglane.serving.service.RulesetCache;
import io.github.sanduniliyanage.flaglane.serving.service.SdkRateLimit;
import io.github.sanduniliyanage.flaglane.serving.service.SdkRateLimit.Cost;
import jakarta.servlet.DispatcherType;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** A stream is charged once, when it opens, not again when it ends (NFR-SEC-005). */
class SdkRateLimitInterceptorTest {

  /** The handler the request is for; the interceptor never looks at it. */
  private static final Object HANDLER = new Object();

  private final SdkRateLimit limit = mock(SdkRateLimit.class);
  private final RulesetCache cache = mock(RulesetCache.class);
  private final SdkRateLimitInterceptor interceptor = new SdkRateLimitInterceptor(limit, cache);
  private final SdkCredential key =
      new SdkCredential(UUID.randomUUID(), UUID.randomUUID(), KeyType.SERVER);

  @BeforeEach
  void authenticated() {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(key, null, List.of()));
    when(cache.get(any())).thenReturn(Optional.empty());
    when(limit.charge(any(), any())).thenReturn(new Decision(true, Duration.ZERO));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void openingAStreamIsChargedAsAFullRequest() {
    boolean proceed =
        interceptor.preHandle(
            new MockHttpServletRequest("GET", "/sdk/stream"),
            new MockHttpServletResponse(),
            HANDLER);

    assertThat(proceed).isTrue();
    verify(limit).charge(eq(key.keyId()), eq(Cost.FULL));
  }

  @Test
  void theDispatchThatEndsAStreamIsNotChargedAgain() {
    MockHttpServletRequest ending = new MockHttpServletRequest("GET", "/sdk/stream");
    ending.setDispatcherType(DispatcherType.ASYNC);

    boolean proceed = interceptor.preHandle(ending, new MockHttpServletResponse(), HANDLER);

    assertThat(proceed).isTrue();
    verifyNoInteractions(limit);
  }
}
