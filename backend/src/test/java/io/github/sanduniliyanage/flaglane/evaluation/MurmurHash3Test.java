package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * MurmurHash3 x86_32 against published verification vectors, each confirmed against the reference C
 * implementation (smhasher's {@code MurmurHash3_x86_32}). They cover every tail length, embedded
 * NUL bytes, an unsigned seed, multi-byte UTF-8, and a 256-byte input.
 */
class MurmurHash3Test {

  static Stream<Arguments> vectors() {
    return Stream.of(
        Arguments.of("", 0, 0x00000000),
        Arguments.of("", 1, 0x514E28B7),
        Arguments.of("", 0xFFFFFFFF, 0x81F16F39),
        Arguments.of("\0\0\0\0", 0, 0x2362F9DE),
        Arguments.of("aaaa", 0x9747B28C, 0x5A97808A),
        Arguments.of("aaa", 0x9747B28C, 0x283E0130),
        Arguments.of("aa", 0x9747B28C, 0x5D211726),
        Arguments.of("a", 0x9747B28C, 0x7FA09EA6),
        Arguments.of("abcd", 0x9747B28C, 0xF0478627),
        Arguments.of("abc", 0x9747B28C, 0xC84A62DD),
        Arguments.of("ab", 0x9747B28C, 0x74875592),
        Arguments.of("Hello, world!", 0x9747B28C, 0x24884CBA),
        Arguments.of("π".repeat(8), 0x9747B28C, 0xD58063C1),
        Arguments.of("a".repeat(256), 0x9747B28C, 0x37405BDC),
        Arguments.of("abc", 0, 0xB3DD93FA),
        Arguments.of("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq", 0, 0xEE925B90),
        Arguments.of("The quick brown fox jumps over the lazy dog", 0x9747B28C, 0x2FA826CD));
  }

  @ParameterizedTest(name = "\"{0}\" seed {1}")
  @MethodSource("vectors")
  void matchesTheReferenceImplementation(String input, int seed, int expected) {
    int hash = MurmurHash3.x86_32(input.getBytes(StandardCharsets.UTF_8), seed);

    assertThat(Integer.toHexString(hash)).isEqualTo(Integer.toHexString(expected));
  }
}
