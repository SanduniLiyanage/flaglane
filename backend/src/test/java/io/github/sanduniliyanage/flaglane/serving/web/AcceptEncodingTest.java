package io.github.sanduniliyanage.flaglane.serving.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

/** RFC 9110 {@code Accept-Encoding}: whether gzip is acceptable to the client. */
class AcceptEncodingTest {

  @ParameterizedTest(name = "[{0}] -> {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "gzip|true",
        "gzip, deflate, br|true",
        "GZIP|true",
        "x-gzip|true",
        "br;q=1.0, gzip;q=0.8|true",
        "gzip;q=0.001|true",
        "*|true",
        "*;q=0.5|true",
        "gzip;q=0|false",
        "gzip;q=0.000|false",
        "gzip; q=0.0, *|false",
        "*;q=0|false",
        "deflate, br|false",
        "identity|false",
        "''|false"
      })
  void gzipIsAcceptableOnlyWhenNamedOrCoveredWithAQualityAboveZero(
      String acceptEncoding, boolean expected) {
    assertThat(SdkController.acceptsGzip(acceptEncoding)).isEqualTo(expected);
  }

  @ParameterizedTest
  @NullSource
  void noHeaderGetsThePlainBody(String acceptEncoding) {
    assertThat(SdkController.acceptsGzip(acceptEncoding)).isFalse();
  }
}
