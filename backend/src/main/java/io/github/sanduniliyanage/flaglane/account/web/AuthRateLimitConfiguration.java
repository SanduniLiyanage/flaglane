package io.github.sanduniliyanage.flaglane.account.web;

import io.github.sanduniliyanage.flaglane.common.security.TokenBuckets;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts the per-address limit in front of {@code /api/auth/**} (ADR-029). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthRateLimitProperties.class)
public class AuthRateLimitConfiguration implements WebMvcConfigurer {

  /**
   * Addresses tracked at once. Each costs one map entry, and only an address active within the last
   * minute needs one, so this is room for a busy instance well short of a flood.
   */
  static final int MAX_ADDRESSES = 10_000;

  private final AuthRateLimitInterceptor interceptor;

  public AuthRateLimitConfiguration(AuthRateLimitProperties properties, Clock clock) {
    this.interceptor =
        new AuthRateLimitInterceptor(
            new TokenBuckets(properties.perMinute(), MAX_ADDRESSES, clock));
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(interceptor).addPathPatterns("/api/auth/**");
  }
}
