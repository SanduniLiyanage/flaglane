package io.github.sanduniliyanage.flaglane.serving.web;

import io.github.sanduniliyanage.flaglane.serving.service.RulesetCache;
import io.github.sanduniliyanage.flaglane.serving.service.SdkRateLimit;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts the per-key limit in front of {@code /sdk/**} (NFR-SEC-005, ADR-035). */
@Configuration(proxyBeanMethods = false)
public class SdkRateLimitConfiguration implements WebMvcConfigurer {

  private final SdkRateLimitInterceptor interceptor;

  public SdkRateLimitConfiguration(SdkRateLimit limit, RulesetCache cache) {
    this.interceptor = new SdkRateLimitInterceptor(limit, cache);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(interceptor).addPathPatterns("/sdk/**");
  }
}
