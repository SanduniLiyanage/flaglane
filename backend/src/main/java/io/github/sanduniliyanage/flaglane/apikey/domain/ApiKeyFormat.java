package io.github.sanduniliyanage.flaglane.apikey.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The key format of FR-KEY-002 and docs/DATABASE.md: a type marker followed by 32 bytes from a
 * CSPRNG, base64url without padding.
 *
 * <pre>
 * flg_srv_&lt;43 characters&gt;     server key
 * flg_cli_&lt;43 characters&gt;     client key
 * </pre>
 *
 * <p>256 bits of entropy is what makes a single SHA-256 the right way to store one: nobody can
 * search that space, so a slow hash would buy nothing but latency on every SDK request (ADR-007).
 */
public final class ApiKeyFormat {

  /** The marker and the first 8 random characters: shown in the dashboard, not secret. */
  public static final int PREFIX_LENGTH = 16;

  private static final int RANDOM_BYTES = 32;
  private static final Pattern WELL_FORMED = Pattern.compile("^flg_(srv|cli)_[A-Za-z0-9_-]{43}$");
  private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

  private ApiKeyFormat() {}

  public static String generate(KeyType type, SecureRandom random) {
    byte[] secret = new byte[RANDOM_BYTES];
    random.nextBytes(secret);
    return type.marker() + BASE64URL.encodeToString(secret);
  }

  public static String prefix(String key) {
    return key.substring(0, PREFIX_LENGTH);
  }

  /** SHA-256 of the whole key, hex-encoded: what {@code api_keys.key_hash} holds. */
  public static String hash(String key) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.US_ASCII));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java runtime provides SHA-256", e);
    }
  }

  /**
   * Whether a presented string could be a key at all. Checked before hashing so that garbage is
   * turned away cheaply; says nothing about whether the key exists.
   */
  public static boolean isWellFormed(String candidate) {
    return candidate != null && WELL_FORMED.matcher(candidate).matches();
  }
}
