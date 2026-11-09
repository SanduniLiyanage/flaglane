package io.github.sanduniliyanage.flaglane.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * Behind a reverse proxy, a request's client address and scheme come from the proxy's forwarded
 * headers, believed from a proxy on a private address only (ADR-037). Each nested class is one
 * deployment. The requests come from this JVM over loopback, which stands for the proxy: Tomcat
 * counts loopback as a private address unless told otherwise.
 *
 * <p>The client address is observed through the sign-in limit (ADR-029), set to 2 a minute, so a
 * third attempt from one address is refused and an attempt from another is not. The scheme is
 * observed through {@code Strict-Transport-Security}, which Spring Security sends on a secure
 * request only.
 */
class ForwardedHeadersTest {

  private static final String LIMIT = "flaglane.security.auth-rate-limit.per-minute=2";

  /** The default: no proxy is declared, so forwarded headers are someone's claim, and ignored. */
  @Nested
  @FlaglaneIntegrationTest
  class WithNoProxyDeclared {

    private final TestRestTemplate http;

    WithNoProxyDeclared(@Autowired TestRestTemplate http) {
      this.http = http;
    }

    @Test
    void aClaimedHttpsSchemeIsIgnored() {
      assertThat(probe(http).getHeaders().containsKey(STRICT_TRANSPORT_SECURITY)).isFalse();
    }
  }

  @Nested
  @FlaglaneIntegrationTest
  @TestPropertySource(properties = {LIMIT, "server.forward-headers-strategy=native"})
  class BehindAProxy {

    private final TestRestTemplate http;

    BehindAProxy(@Autowired TestRestTemplate http) {
      this.http = http;
    }

    @Test
    void eachForwardedClientHasAnAllowanceOfItsOwn() {
      HttpStatusCode first = signIn(http, "198.51.100.1").getStatusCode();
      HttpStatusCode second = signIn(http, "198.51.100.1").getStatusCode();
      HttpStatusCode third = signIn(http, "198.51.100.1").getStatusCode();
      HttpStatusCode another = signIn(http, "198.51.100.2").getStatusCode();

      assertThat(first).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(second).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(third).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
      assertThat(another).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * A client sends {@code X-Forwarded-For} of its own; the proxy appends the address it saw. The
     * client is the rightmost address, so what it wrote buys it nothing.
     */
    @Test
    void aClientCannotNameItsOwnAddressThroughTheProxy() {
      HttpStatusCode first = signIn(http, "203.0.113.1, 198.51.100.3").getStatusCode();
      HttpStatusCode second = signIn(http, "203.0.113.2, 198.51.100.3").getStatusCode();
      HttpStatusCode third = signIn(http, "203.0.113.3, 198.51.100.3").getStatusCode();

      assertThat(first).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(second).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(third).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void aRequestTheProxyReceivedOverHttpsIsSecure() {
      assertThat(probe(http).getHeaders().containsKey(STRICT_TRANSPORT_SECURITY)).isTrue();
    }
  }

  /** A proxy is declared, at an address other than the peer's: the peer is a client like any. */
  @Nested
  @FlaglaneIntegrationTest
  @TestPropertySource(
      properties = {
        LIMIT,
        "server.forward-headers-strategy=native",
        "server.tomcat.remoteip.internal-proxies=192\\.0\\.2\\.1"
      })
  class FromAPeerThatIsNotTheProxy {

    private final TestRestTemplate http;

    FromAPeerThatIsNotTheProxy(@Autowired TestRestTemplate http) {
      this.http = http;
    }

    @Test
    void forwardedAddressesAreIgnoredAndThePeerHasOneAllowance() {
      HttpStatusCode first = signIn(http, "198.51.100.10").getStatusCode();
      HttpStatusCode second = signIn(http, "198.51.100.11").getStatusCode();
      HttpStatusCode third = signIn(http, "198.51.100.12").getStatusCode();

      assertThat(first).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(second).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(third).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void aClaimedHttpsSchemeIsIgnored() {
      assertThat(probe(http).getHeaders().containsKey(STRICT_TRANSPORT_SECURITY)).isFalse();
    }
  }

  private static final String STRICT_TRANSPORT_SECURITY = "Strict-Transport-Security";

  /** A sign-in for an account that does not exist, as forwarded for {@code forwardedFor}. */
  private static ResponseEntity<String> signIn(TestRestTemplate http, String forwardedFor) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Forwarded-For", forwardedFor);
    Map<String, String> body =
        Map.of(
            "email",
            "nobody-" + UUID.randomUUID() + "@example.com",
            "password",
            "correct-horse-battery");
    return http.exchange(
        "/api/auth/login", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }

  /** The liveness probe, as a proxy that received it over HTTPS would forward it. */
  private static ResponseEntity<String> probe(TestRestTemplate http) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Forwarded-For", "198.51.100.99");
    headers.set("X-Forwarded-Proto", "https");
    ResponseEntity<String> response =
        http.exchange(
            "/actuator/health/liveness", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response;
  }
}
