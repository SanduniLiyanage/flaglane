package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * FR-EVL-002, clause by clause. Every expected value here was computed by the reference C
 * implementation of MurmurHash3 x86_32 over the UTF-8 bytes of {@code salt + ":" + userKey}, not by
 * this code, so a test passing means agreement with the specification rather than with itself.
 */
class BucketingTest {

  private static final String SALT = "new-checkout";

  static Stream<Arguments> referenceBuckets() {
    return Stream.of(
        // Hash 0x84f04c17. ASCII.
        Arguments.of("user-1", 631),
        // Hash 0xe758950f, high bit set: left signed, Java's % would give -5073.
        Arguments.of("u-1042", 2223),
        Arguments.of("d35acb56-1b11-4db7-a92e-09e7dad8c5d8", 3116),
        Arguments.of("", 7121),
        // The separator inside the user key as well as before it.
        Arguments.of("a:b", 7400),
        Arguments.of("tenant:7:user:42", 3124),
        // U+00E9 precomposed, then e + U+0301 combining: no normalisation, so different buckets.
        Arguments.of("é", 8524),
        Arguments.of("é", 1018),
        // Sinhala, then CJK: three bytes per code point in UTF-8, one char each in UTF-16.
        Arguments.of("සඳුනි", 5445),
        Arguments.of("北京", 910),
        // U+1F600: four bytes in UTF-8, a surrogate pair in UTF-16.
        Arguments.of("😀", 6126),
        Arguments.of("𠀀:ü", 9754),
        // Lone surrogates have no UTF-8 encoding. Each becomes U+FFFD, as TextEncoder does in the
        // TypeScript SDK, not '?' as String.getBytes does (ADR-018).
        Arguments.of("\ud83d", 8716),
        Arguments.of("x\ude00y", 3250),
        Arguments.of("\ude00\ud83d", 4945));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("referenceBuckets")
  void bucketMatchesTheReferenceImplementation(String userKey, int expected) {
    assertThat(Bucketing.bucket(SALT, userKey)).isEqualTo(expected);
  }

  @Test
  void everyBucketOfTheCommittedKeySetMatchesTheReferenceImplementation()
      throws NoSuchAlgorithmException {
    List<String> keys = UserKeyFixture.keys();
    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

    for (String key : keys) {
      sha256.update((Bucketing.bucket(SALT, key) + "\n").getBytes(StandardCharsets.US_ASCII));
    }

    // SHA-256 of each key's bucket in file order, one decimal number per line, as computed by the
    // reference implementation. A change here reshuffles users: FR-EVL-003 says that never happens.
    assertThat(HexFormat.of().formatHex(sha256.digest()))
        .isEqualTo("48a9483310f48e264ba8e00b17dabcf8c1527917f4438f728525d252b7d1c99f");
  }

  @Test
  void everyBucketIsBetweenZeroAndNineThousandNineHundredNinetyNine() {
    assertThat(UserKeyFixture.keys())
        .allSatisfy(key -> assertThat(Bucketing.bucket(SALT, key)).isBetween(0, 9_999));
  }

  @Test
  void aKeyIsInTheRolloutExactlyWhenItsBucketIsBelowTheRollout() {
    // "user-1" is in bucket 631.
    assertThat(Bucketing.isInRollout(SALT, "user-1", 0)).isFalse();
    assertThat(Bucketing.isInRollout(SALT, "user-1", 631)).isFalse();
    assertThat(Bucketing.isInRollout(SALT, "user-1", 632)).isTrue();
    assertThat(Bucketing.isInRollout(SALT, "user-1", 10_000)).isTrue();
  }

  @Test
  void flagConfigBucketsWithItsSaltAndRollout() {
    FlagConfig at631 =
        FlagConfig.builder("other-flag").rolloutSalt(SALT).rolloutBasisPoints(631).build();
    FlagConfig at632 = at631.toBuilder().rolloutBasisPoints(632).build();

    assertThat(at631.isInRollout("user-1")).isFalse();
    assertThat(at632.isInRollout("user-1")).isTrue();
  }
}
