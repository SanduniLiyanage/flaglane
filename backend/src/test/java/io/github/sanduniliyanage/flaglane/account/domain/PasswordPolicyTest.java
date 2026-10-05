package io.github.sanduniliyanage.flaglane.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

  @Test
  void fifteenCharactersIsEnough() {
    assertThat(PasswordPolicy.violation("correct-horse-b")).isEmpty();
  }

  @Test
  void fourteenCharactersIsTooShort() {
    assertThat(PasswordPolicy.violation("correct-horse-"))
        .contains("password must be at least 15 characters");
  }

  @Test
  void lengthCountsCharactersNotUtf16Units() {
    // Fifteen emoji: thirty UTF-16 units, sixty UTF-8 bytes, fifteen characters.
    String fifteenEmoji = "😀".repeat(15);
    String fourteenEmoji = "😀".repeat(14);

    assertThat(PasswordPolicy.violation(fifteenEmoji)).isEmpty();
    assertThat(PasswordPolicy.violation(fourteenEmoji)).isPresent();
  }

  @Test
  void seventyTwoBytesIsTheLongestBcryptReads() {
    assertThat(PasswordPolicy.violation("a".repeat(72))).isEmpty();
    assertThat(PasswordPolicy.violation("a".repeat(73)))
        .contains("password must be at most 72 bytes in UTF-8");
  }

  @Test
  void theByteLimitIsMeasuredInUtf8() {
    // Twenty-five three-byte characters: 25 characters, 75 bytes.
    assertThat(PasswordPolicy.violation("ස".repeat(25))).isPresent();
    assertThat(PasswordPolicy.violation("ස".repeat(24))).isEmpty();
  }

  @Test
  void aMissingPasswordIsAViolation() {
    assertThat(PasswordPolicy.violation(null)).contains("password is required");
  }
}
