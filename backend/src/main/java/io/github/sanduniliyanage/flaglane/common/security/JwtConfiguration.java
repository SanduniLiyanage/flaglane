package io.github.sanduniliyanage.flaglane.common.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Dashboard access tokens: HS256 JWTs signed and verified with one symmetric secret (ADR-021).
 * Issuance lives with accounts; verification is here, where the filter chain finds it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {

  /** The {@code iss} claim of every token Flaglane issues, and the only one it accepts. */
  public static final String ISSUER = "flaglane";

  /** Tolerated disagreement between the clock that issued a token and the one checking it. */
  static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

  @Bean
  JwtEncoder jwtEncoder(JwtProperties properties) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(properties.signingKey()));
  }

  @Bean
  JwtDecoder jwtDecoder(JwtProperties properties, Clock clock) {
    return decoder(properties, clock);
  }

  /**
   * Verifies the signature with HS256 only — a token claiming {@code none} or another algorithm is
   * rejected before its claims are read — then requires an expiry, checks it against {@code clock},
   * and requires Flaglane as the issuer.
   */
  static NimbusJwtDecoder decoder(JwtProperties properties, Clock clock) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(properties.signingKey())
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
    timestamps.setClock(clock);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
            timestamps,
            new JwtIssuerValidator(ISSUER)));
    return decoder;
  }
}
