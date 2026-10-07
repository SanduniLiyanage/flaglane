package io.github.sanduniliyanage.flaglane.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * One filter chain per API surface (ADR-006), so a mistake in one cannot widen the other.
 *
 * <p>{@code /api/**} is the dashboard's, authenticated by a JWT bearer token, with registration and
 * sign-in the only open endpoints. {@code /sdk/**} gets its own chain, authenticated by API key, in
 * slice 1.8. Everything else is closed except health probes, the API documentation, Spring's error
 * page and the dashboard's page, assets and routes.
 *
 * <p>All chains are stateless and have no CSRF protection: credentials travel in an {@code
 * Authorization} header that a browser never attaches on its own, so there is no ambient credential
 * for a cross-site request to borrow.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

  @Bean
  @Order(1)
  SecurityFilterChain managementApi(HttpSecurity http) throws Exception {
    stateless(http)
        .securityMatcher("/api/**")
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
    return http.build();
  }

  @Bean
  @Order(Integer.MAX_VALUE)
  SecurityFilterChain everythingElse(HttpSecurity http) throws Exception {
    stateless(http)
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        // Where the container forwards an error, a 404 from /api/** included.
                        // Closing it would turn every such status into a 403.
                        "/error")
                    .permitAll()
                    // The dashboard, served from this origin so that it never needs CORS
                    // (ADR-031): its page, its built assets, and the routes DashboardRoutes
                    // answers with the page. Read-only; anything else is still denied.
                    .requestMatchers(
                        HttpMethod.GET,
                        "/",
                        "/index.html",
                        "/favicon.svg",
                        "/assets/**",
                        "/sign-in",
                        "/projects",
                        "/projects/**")
                    .permitAll()
                    .anyRequest()
                    .denyAll());
    return http.build();
  }

  /**
   * bcrypt, behind Spring Security's delegating encoder, so each stored hash names its algorithm
   * ({@code {bcrypt}...}) and a later change of algorithm upgrades hashes as users sign in rather
   * than invalidating them (FR-ACC-001).
   */
  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  /** Settings every chain shares: no session, no CSRF, no browser login flows. */
  public static HttpSecurity stateless(HttpSecurity http) throws Exception {
    return http.sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable);
  }
}
