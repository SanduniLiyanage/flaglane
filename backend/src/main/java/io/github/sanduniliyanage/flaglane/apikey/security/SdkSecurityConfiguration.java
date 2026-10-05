package io.github.sanduniliyanage.flaglane.apikey.security;

import io.github.sanduniliyanage.flaglane.apikey.service.ApiKeyCache;
import io.github.sanduniliyanage.flaglane.apikey.service.LastUsedRecorder;
import io.github.sanduniliyanage.flaglane.common.security.SecurityConfiguration;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The serving API's own filter chain (ADR-006): {@code /sdk/**}, authenticated by API key and by
 * nothing else. A dashboard token is not a key here, and a key is not a token on {@code /api/**}.
 *
 * <p>Every response, a 401 included, is {@code Cache-Control: private, no-store} with {@code Vary:
 * Authorization}. One URL serves different bodies to different keys, so no intermediary may store
 * one and hand it to another caller (FR-SRV-001).
 *
 * <p>Any origin may call it from a browser, which is what client keys are for (ADR-025). Only this
 * chain allows cross-origin requests; the dashboard API allows none.
 */
@Configuration(proxyBeanMethods = false)
public class SdkSecurityConfiguration {

  @Bean
  @Order(2)
  SecurityFilterChain servingApi(HttpSecurity http, ApiKeyCache keys, LastUsedRecorder lastUsed)
      throws Exception {
    SecurityConfiguration.stateless(http)
        .securityMatcher("/sdk/**")
        .cors(cors -> cors.configurationSource(anyOrigin()))
        .headers(headers -> headers.cacheControl(cacheControl -> cacheControl.disable()))
        .addFilterBefore(new PrivateResponseFilter(), HeaderWriterFilter.class)
        .addFilterBefore(new ApiKeyAuthenticationFilter(keys, lastUsed), AuthorizationFilter.class)
        .authorizeHttpRequests(requests -> requests.anyRequest().authenticated());
    return http.build();
  }

  /**
   * Any origin, without credentials: the key travels in a header the page sets itself, never in a
   * cookie a browser would attach on its own, so allowing every origin lets no site do anything a
   * holder of the key could not already do. The ETag is exposed so a browser SDK can make its
   * conditional requests.
   */
  static CorsConfigurationSource anyOrigin() {
    CorsConfiguration cors = new CorsConfiguration();
    cors.setAllowedOrigins(List.of(CorsConfiguration.ALL));
    cors.setAllowedMethods(List.of("GET", "POST"));
    cors.setAllowedHeaders(
        List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.IF_NONE_MATCH, HttpHeaders.CONTENT_TYPE));
    cors.setExposedHeaders(List.of(HttpHeaders.ETAG, HttpHeaders.RETRY_AFTER));
    cors.setAllowCredentials(false);
    cors.setMaxAge(Duration.ofHours(1));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/sdk/**", cors);
    return source;
  }
}
