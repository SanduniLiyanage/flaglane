package io.github.sanduniliyanage.flaglane.serving.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** RFC 9110 {@code If-None-Match} against the ETag {@code "412-server"}. */
class IfNoneMatchTest {

  @ParameterizedTest(name = "[{0}] -> {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "\"412-server\"|true",
        "W/\"412-server\"|true",
        "\"411-server\", \"412-server\"|true",
        "*|true",
        "\"412-client\"|false",
        "\"413-server\"|false",
        "412-server|false"
      })
  void matchesOnlyTheExactTagWeakOrStrong(String ifNoneMatch, boolean expected) {
    assertThat(SdkController.matches(ifNoneMatch, "\"412-server\"")).isEqualTo(expected);
  }

  @Test
  void noHeaderMatchesNothing() {
    assertThat(SdkController.matches(null, "\"412-server\"")).isFalse();
  }
}
