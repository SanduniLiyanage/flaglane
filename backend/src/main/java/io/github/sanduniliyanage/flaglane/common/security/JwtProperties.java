package io.github.sanduniliyanage.flaglane.common.security;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The access token signing secret, from {@code FLAGLANE_JWT_SECRET} (ADR-021).
 *
 * @param secret at least 32 bytes, because HS256 with a shorter key is brute-forceable and Nimbus
 *     refuses one anyway, later and less clearly
 */
@ConfigurationProperties("flaglane.security.jwt")
public record JwtProperties(String secret) {

  /** HS256 needs a key at least as long as its 256-bit output. */
  static final int MIN_SECRET_BYTES = 32;

  public JwtProperties {
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
      throw new IllegalArgumentException(
          "FLAGLANE_JWT_SECRET must be at least "
              + MIN_SECRET_BYTES
              + " bytes; generate one with: openssl rand -base64 48");
    }
  }

  SecretKey signingKey() {
    return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  /** Never prints the secret, whoever logs this object. */
  @Override
  public String toString() {
    return "JwtProperties[secret=<redacted>]";
  }
}
