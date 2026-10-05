package io.github.sanduniliyanage.flaglane.evaluation;

import java.nio.charset.StandardCharsets;

/**
 * Assigns a user to one of 10,000 buckets for a rollout, as FR-EVL-002 specifies clause by clause:
 *
 * <pre>
 * bucket = murmur3_x86_32(utf8(rolloutSalt + ":" + userKey), seed = 0) unsigned % 10000
 * </pre>
 *
 * <p>Every clause is load-bearing for parity with the TypeScript SDK. The bucket depends on the
 * salt and the user key and on nothing else — not the rollout, not the process, not the clock —
 * which is what makes it deterministic (FR-EVL-003), independent across flags with different salts
 * (FR-EVL-004) and monotone as the rollout rises (FR-EVL-008).
 */
public final class Bucketing {

  /** Buckets run 0 to 9999, so a rollout counts in basis points (ADR-011). */
  public static final int BUCKET_COUNT = 10_000;

  private static final int SEED = 0;
  private static final char REPLACEMENT_CHARACTER = '�';

  private Bucketing() {}

  /** The user's bucket for this salt: an integer from 0 to 9999, the same forever. */
  public static int bucket(String rolloutSalt, String userKey) {
    int hash = MurmurHash3.x86_32(utf8(rolloutSalt + ":" + userKey), SEED);
    // Widened unsigned before the modulo. Left signed, Java yields a negative bucket where
    // JavaScript's (h >>> 0) yields a positive one for the same input.
    return (int) (Integer.toUnsignedLong(hash) % BUCKET_COUNT);
  }

  /**
   * Whether a rollout of {@code rolloutBasisPoints} reaches the user. Strictly less than: a rollout
   * of 0 reaches nobody, 10000 reaches everybody, and raising it only ever adds users.
   */
  public static boolean isInRollout(String rolloutSalt, String userKey, int rolloutBasisPoints) {
    return bucket(rolloutSalt, userKey) < rolloutBasisPoints;
  }

  /**
   * UTF-8, with each unpaired surrogate encoded as U+FFFD, which is what the WHATWG encoder behind
   * {@code TextEncoder} does. {@code String.getBytes} substitutes {@code '?'} instead, and the two
   * would bucket the same malformed key differently (ADR-018).
   */
  static byte[] utf8(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)
          && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        i++;
      } else if (Character.isSurrogate(c)) {
        return replaceUnpairedSurrogates(text).getBytes(StandardCharsets.UTF_8);
      }
    }
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String replaceUnpairedSurrogates(String text) {
    StringBuilder replaced = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)
          && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        replaced.append(c).append(text.charAt(++i));
      } else if (Character.isSurrogate(c)) {
        replaced.append(REPLACEMENT_CHARACTER);
      } else {
        replaced.append(c);
      }
    }
    return replaced.toString();
  }
}
