package io.github.sanduniliyanage.flaglane.account.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Which addresses share a sign-in allowance. */
class AuthRateLimitInterceptorTest {

  @Test
  void anIpv4AddressIsItsOwnKey() {
    assertThat(AuthRateLimitInterceptor.clientKey("203.0.113.7")).isEqualTo("203.0.113.7");
    assertThat(AuthRateLimitInterceptor.clientKey("203.0.113.8")).isEqualTo("203.0.113.8");
  }

  @Test
  void everySpellingOfEveryAddressInAnIpv6Slash64IsOneKey() {
    String key = AuthRateLimitInterceptor.clientKey("2001:db8:1:2::1");

    assertThat(AuthRateLimitInterceptor.clientKey("2001:0db8:0001:0002:0000:0000:0000:0001"))
        .isEqualTo(key);
    assertThat(AuthRateLimitInterceptor.clientKey("2001:DB8:1:2:ffff:ffff:ffff:ffff"))
        .isEqualTo(key);
    assertThat(key).isEqualTo("20010db800010002/64");
  }

  @Test
  void neighbouringIpv6Slash64sAreDifferentKeys() {
    assertThat(AuthRateLimitInterceptor.clientKey("2001:db8:1:3::1"))
        .isNotEqualTo(AuthRateLimitInterceptor.clientKey("2001:db8:1:2::1"));
  }

  @Test
  void anIpv4MappedIpv6AddressIsTheIpv4Address() {
    assertThat(AuthRateLimitInterceptor.clientKey("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
  }

  @Test
  void somethingThatIsNotAnAddressIsKeyedAsItIsAndNeverLookedUp() {
    assertThat(AuthRateLimitInterceptor.clientKey("unknown")).isEqualTo("unknown");
    assertThat(AuthRateLimitInterceptor.clientKey("g::1")).isEqualTo("g::1");
    assertThat(AuthRateLimitInterceptor.clientKey(null)).isEmpty();
  }
}
