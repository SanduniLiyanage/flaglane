package io.github.sanduniliyanage.flaglane.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.sanduniliyanage.flaglane.account.service.IssuedToken;
import io.github.sanduniliyanage.flaglane.account.service.TokenIssuer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * Access tokens end to end, outside Spring: what {@link TokenIssuer} signs, the decoder the filter
 * chain uses accepts, and nothing else does (FR-ACC-002, ADR-021).
 */
class AccessTokenTest {

  private static final JwtProperties PROPERTIES =
      new JwtProperties("a-test-secret-that-is-at-least-thirty-two-bytes");
  private static final Instant ISSUED = Instant.parse("2026-10-05T09:00:00Z");
  private static final UUID USER = UUID.fromString("0f6b3b5e-7c2a-4f5e-9d61-2a4b8f0e1c3d");

  private final JwtEncoder encoder = new JwtConfiguration().jwtEncoder(PROPERTIES);
  private final IssuedToken token =
      new TokenIssuer(encoder, Clock.fixed(ISSUED.plusMillis(750), ZoneOffset.UTC)).issue(USER);

  @Test
  void anIssuedTokenNamesTheUserAndExpiresEightHoursLater() {
    Jwt jwt = decoderAt(ISSUED).decode(token.value());

    assertThat(jwt.getSubject()).isEqualTo(USER.toString());
    assertThat(jwt.getClaimAsString("iss")).isEqualTo("flaglane");
    assertThat(jwt.getIssuedAt()).isEqualTo(ISSUED);
    assertThat(jwt.getExpiresAt()).isEqualTo(ISSUED.plus(Duration.ofHours(8)));
    assertThat(token.expiresAt()).isEqualTo(jwt.getExpiresAt());
    assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
  }

  @Test
  void aTokenIsAcceptedUntilItExpires() {
    Instant lastMoment = ISSUED.plus(Duration.ofHours(8)).plus(JwtConfiguration.CLOCK_SKEW);

    assertThat(decoderAt(lastMoment).decode(token.value()).getSubject()).isEqualTo(USER.toString());
  }

  @Test
  void anExpiredTokenIsRejected() {
    Instant afterExpiry =
        ISSUED.plus(Duration.ofHours(8)).plus(JwtConfiguration.CLOCK_SKEW).plusSeconds(1);

    assertThatExceptionOfType(JwtValidationException.class)
        .isThrownBy(() -> decoderAt(afterExpiry).decode(token.value()))
        .withMessageContaining("expired");
  }

  @Test
  void aTokenSignedWithAnotherSecretIsRejected() {
    JwtProperties other = new JwtProperties("a-different-secret-also-thirty-two-bytes-long");
    IssuedToken forged =
        new TokenIssuer(
                new JwtConfiguration().jwtEncoder(other), Clock.fixed(ISSUED, ZoneOffset.UTC))
            .issue(USER);

    assertThatExceptionOfType(BadJwtException.class)
        .isThrownBy(() -> decoderAt(ISSUED).decode(forged.value()));
  }

  @Test
  void aTokenWhosePayloadWasEditedIsRejected() {
    String[] parts = token.value().split("\\.");
    String otherUser =
        new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
            .replace(USER.toString(), UUID.randomUUID().toString());
    String tampered = parts[0] + "." + base64Url(otherUser) + "." + parts[2];

    assertThatExceptionOfType(BadJwtException.class)
        .isThrownBy(() -> decoderAt(ISSUED).decode(tampered));
  }

  @Test
  void anUnsignedTokenIsRejected() {
    String unsigned =
        base64Url("{\"alg\":\"none\"}")
            + "."
            + base64Url(
                "{\"sub\":\""
                    + USER
                    + "\",\"iss\":\"flaglane\",\"exp\":"
                    + ISSUED.plusSeconds(60).getEpochSecond()
                    + "}")
            + ".";

    assertThatExceptionOfType(JwtException.class)
        .isThrownBy(() -> decoderAt(ISSUED).decode(unsigned));
  }

  @Test
  void aTokenSignedWithAnotherAlgorithmIsRejectedEvenWithTheRightSecret() {
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("flaglane")
            .subject(USER.toString())
            .expiresAt(ISSUED.plusSeconds(60))
            .build();
    String hs512 =
        new JwtConfiguration()
            .jwtEncoder(new JwtProperties("x".repeat(64)))
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS512).build(), claims))
            .getTokenValue();

    assertThatExceptionOfType(BadJwtException.class)
        .isThrownBy(
            () ->
                JwtConfiguration.decoder(
                        new JwtProperties("x".repeat(64)), Clock.fixed(ISSUED, ZoneOffset.UTC))
                    .decode(hs512));
  }

  @Test
  void aTokenFromAnotherIssuerIsRejected() {
    String foreign =
        sign(
            JwtClaimsSet.builder()
                .issuer("someone-else")
                .subject(USER.toString())
                .expiresAt(ISSUED.plusSeconds(60))
                .build());

    assertThatExceptionOfType(JwtValidationException.class)
        .isThrownBy(() -> decoderAt(ISSUED).decode(foreign));
  }

  @Test
  void aTokenWithoutAnExpiryIsRejected() {
    String everlasting =
        sign(JwtClaimsSet.builder().issuer("flaglane").subject(USER.toString()).build());

    assertThatExceptionOfType(JwtValidationException.class)
        .isThrownBy(() -> decoderAt(ISSUED).decode(everlasting));
  }

  @Test
  void aSecretShorterThanThirtyTwoBytesIsRefusedAtStartup() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new JwtProperties("x".repeat(31)))
        .withMessageContaining("FLAGLANE_JWT_SECRET must be at least 32 bytes");
    assertThatIllegalArgumentException().isThrownBy(() -> new JwtProperties(null));
  }

  @Test
  void theSecretIsNeverPrinted() {
    assertThat(PROPERTIES.toString()).doesNotContain(PROPERTIES.secret());
    assertThat(token.toString()).doesNotContain(token.value());
  }

  private static JwtDecoder decoderAt(Instant now) {
    return JwtConfiguration.decoder(PROPERTIES, Clock.fixed(now, ZoneOffset.UTC));
  }

  private String sign(JwtClaimsSet claims) {
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }

  private static String base64Url(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }
}
