package io.github.sanduniliyanage.flaglane.apikey.security;

import io.github.sanduniliyanage.flaglane.apikey.service.ApiKeyCache;
import io.github.sanduniliyanage.flaglane.apikey.service.LastUsedRecorder;
import io.github.sanduniliyanage.flaglane.common.security.SecurityConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;

/**
 * The serving API's own filter chain (ADR-006): {@code /sdk/**}, authenticated by API key and by
 * nothing else. A dashboard token is not a key here, and a key is not a token on {@code /api/**}.
 *
 * <p>Every response, a 401 included, is {@code Cache-Control: private, no-store} with {@code Vary:
 * Authorization}. One URL serves different bodies to different keys, so no intermediary may store
 * one and hand it to another caller (FR-SRV-001).
 */
@Configuration(proxyBeanMethods = false)
public class SdkSecurityConfiguration {

  @Bean
  @Order(2)
  SecurityFilterChain servingApi(HttpSecurity http, ApiKeyCache keys, LastUsedRecorder lastUsed)
      throws Exception {
    SecurityConfiguration.stateless(http)
        .securityMatcher("/sdk/**")
        .headers(headers -> headers.cacheControl(cacheControl -> cacheControl.disable()))
        .addFilterBefore(new PrivateResponseFilter(), HeaderWriterFilter.class)
        .addFilterBefore(new ApiKeyAuthenticationFilter(keys, lastUsed), AuthorizationFilter.class)
        .authorizeHttpRequests(requests -> requests.anyRequest().authenticated());
    return http.build();
  }
}
