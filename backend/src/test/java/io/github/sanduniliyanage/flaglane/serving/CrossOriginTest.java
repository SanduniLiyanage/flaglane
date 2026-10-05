package io.github.sanduniliyanage.flaglane.serving;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * A browser on any origin can call the serving API with a client key, and read its ETag; it cannot
 * call the dashboard API at all (ADR-025).
 */
@FlaglaneIntegrationTest
class CrossOriginTest {

  private static final String ORIGIN = "https://shop.example";

  private final TestRestTemplate http;

  CrossOriginTest(@Autowired TestRestTemplate http) {
    this.http = http;
  }

  @Test
  void aPreflightForTheServingApiIsAllowedFromAnyOrigin() {
    ResponseEntity<String> preflight = preflight("/sdk/config", "authorization,if-none-match");

    assertThat(preflight.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(preflight.getHeaders().getAccessControlAllowOrigin()).isEqualTo("*");
    assertThat(preflight.getHeaders().getAccessControlAllowHeaders())
        .map(String::toLowerCase)
        .contains("authorization", "if-none-match");
    assertThat(preflight.getHeaders().getAccessControlAllowCredentials()).isFalse();
  }

  @Test
  void aServingResponseExposesItsEtagToTheBrowser() {
    HttpHeaders headers = new HttpHeaders();
    headers.setOrigin(ORIGIN);

    ResponseEntity<String> response =
        http.exchange("/sdk/config", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    // A 401 without a key: still readable by the page, so a wrong key is diagnosable.
    assertThat(response.getStatusCode().value()).isEqualTo(401);
    assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("*");
    assertThat(response.getHeaders().getAccessControlExposeHeaders())
        .map(String::toLowerCase)
        .contains("etag");
  }

  @Test
  void theDashboardApiAllowsNoOtherOrigin() {
    ResponseEntity<String> preflight = preflight("/api/projects", "authorization");

    assertThat(preflight.getHeaders().getAccessControlAllowOrigin()).isNull();
  }

  private ResponseEntity<String> preflight(String path, String requestHeaders) {
    HttpHeaders headers = new HttpHeaders();
    headers.setOrigin(ORIGIN);
    headers.setAccessControlRequestMethod(HttpMethod.GET);
    headers.setAccessControlRequestHeaders(List.of(requestHeaders.split(",")));
    return http.exchange(path, HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
  }
}
