package io.github.sanduniliyanage.flaglane.account.domain;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * What a new password must be (ADR-021). Length is the only composition rule, as NIST SP 800-63B
 * recommends; character-class rules make passwords harder to remember and no harder to guess.
 */
public final class PasswordPolicy {

  /** Characters, counted as code points. NIST SP 800-63B-4's floor for a single factor. */
  public static final int MIN_LENGTH = 15;

  /**
   * bcrypt reads at most 72 bytes. A longer password would be silently truncated, so that any two
   * sharing their first 72 bytes are the same password; it is refused instead.
   */
  public static final int MAX_BYTES = 72;

  private PasswordPolicy() {}

  /** Why the password is unacceptable, or empty when it is acceptable. */
  public static Optional<String> violation(String password) {
    if (password == null) {
      return Optional.of("password is required");
    }
    if (password.codePointCount(0, password.length()) < MIN_LENGTH) {
      return Optional.of("password must be at least " + MIN_LENGTH + " characters");
    }
    if (exceedsMaxBytes(password)) {
      return Optional.of("password must be at most " + MAX_BYTES + " bytes in UTF-8");
    }
    return Optional.empty();
  }

  public static boolean exceedsMaxBytes(String password) {
    return password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
  }
}
