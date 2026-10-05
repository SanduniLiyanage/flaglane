package io.github.sanduniliyanage.flaglane.account.service;

import io.github.sanduniliyanage.flaglane.common.security.JwtConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues the single access token a sign-in produces (FR-ACC-002): a JWT naming the user, valid for
 * eight hours, with no refresh token behind it (ADR-012).
 */
@Component
public class TokenIssuer {

  /** FR-ACC-002. Fixed rather than configurable: SECURITY.md states the window. */
  static final Duration LIFETIME = Duration.ofHours(8);

  private final JwtEncoder encoder;
  private final Clock clock;

  public TokenIssuer(JwtEncoder encoder, Clock clock) {
    this.encoder = encoder;
    this.clock = clock;
  }

  public IssuedToken issue(UUID userId) {
    // JWT times are whole seconds; truncating here makes expiresAt exactly the token's exp.
    Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    Instant expiresAt = issuedAt.plus(LIFETIME);
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(JwtConfiguration.ISSUER)
            .subject(userId.toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new IssuedToken(token, expiresAt);
  }
}
