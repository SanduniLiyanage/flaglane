package io.github.sanduniliyanage.flaglane.serving;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import io.github.sanduniliyanage.flaglane.ManagementApiClient;
import io.github.sanduniliyanage.flaglane.serving.service.SdkRateLimit;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The serving API's limit per key (NFR-SEC-005, ADR-035), through the whole application: filter
 * chain, interceptor, exception handler, and the revoke that drops a bucket. The limit is 3 a
 * minute here, thirty tenths, and the clock only moves when a test moves it, so every count below
 * is exact. Sign-in is limited too, by address, so its limit is raised out of the way.
 */
@FlaglaneIntegrationTest
@TestPropertySource(
    properties = {
      "flaglane.security.sdk-rate-limit.per-minute=3",
      "flaglane.security.auth-rate-limit.per-minute=60000"
    })
class SdkRateLimitEndToEndTest {

  private final ManagementApiClient api;
  private final TestRestTemplate http;
  private final ObjectMapper json;
  private final SdkRateLimit limit;
  private final StoppedClock clock;

  private String token;
  private String project;

  SdkRateLimitEndToEndTest(
      @Autowired TestRestTemplate http,
      @Autowired ObjectMapper json,
      @Autowired SdkRateLimit limit,
      @Autowired StoppedClock clock) {
    this.api = new ManagementApiClient(http, json);
    this.http = http;
    this.json = json;
    this.limit = limit;
    this.clock = clock;
  }

  @TestConfiguration
  static class StoppedClockConfiguration {

    @Bean
    @Primary
    StoppedClock stoppedClock() {
      return new StoppedClock(Instant.now());
    }
  }

  @BeforeEach
  void aProjectWithAFlag() {
    token = api.signUp("limited");
    project = api.createProject(token, "limited");
    api.post(
        "/api/projects/" + project + "/flags", token, Map.of("key", "banner", "name", "Banner"));
  }

  @Test
  void aKeysFourthFullAnswerInAMinuteIsRefusedWithRetryAfter() throws Exception {
    String key = issue().key();
    for (int i = 0; i < 3; i++) {
      assertThat(config(key, null).getStatusCode())
          .as("request %d", i + 1)
          .isEqualTo(HttpStatus.OK);
    }

    ResponseEntity<String> refused = config(key, null);
    JsonNode problem = json.readTree(refused.getBody());

    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
        .as("ten tenths at two seconds each")
        .isEqualTo("20");
    assertThat(refused.getHeaders().getContentType())
        .matches(type -> type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    assertThat(problem.path("status").asInt()).isEqualTo(429);
    assertThat(problem.path("title").asText()).isEqualTo("Too Many Requests");
    assertThat(problem.path("detail").asText()).doesNotContain(key);
    assertThat(refused.getHeaders().getCacheControl()).isEqualTo("private, no-store");
    assertThat(refused.getHeaders().getVary()).contains("Authorization");
  }

  @Test
  void aNotModifiedCostsATenthOfAFullAnswer() {
    String key = issue().key();
    String etag = config(key, null).getHeaders().getETag();
    for (int i = 0; i < 20; i++) {
      assertThat(config(key, etag).getStatusCode())
          .as("poll %d", i + 1)
          .isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    ResponseEntity<String> refused = config(key, etag);

    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
  }

  @Test
  void anEtagThatNoLongerMatchesIsChargedAsTheFullAnswerItGets() {
    String key = issue().key();
    for (int i = 0; i < 3; i++) {
      assertThat(config(key, "\"0-server\"").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    assertThat(config(key, "\"0-server\"").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void evaluationsAndDownloadsShareTheKeysAllowance() {
    String key = issue().key();
    assertThat(
            evaluate(key, "{\"flags\":[{\"key\":\"banner\",\"fallback\":false}]}").getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(config(key, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(
            evaluate(key, "{\"flags\":[{\"key\":\"banner\",\"fallback\":false}]}").getStatusCode())
        .isEqualTo(HttpStatus.OK);

    ResponseEntity<String> refused =
        evaluate(key, "{\"flags\":[{\"key\":\"banner\",\"fallback\":false}]}");

    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void aRefusedRequestIsRefusedBeforeItsBodyIsRead() {
    String key = issue().key();
    for (int i = 0; i < 3; i++) {
      config(key, null);
    }

    assertThat(evaluate(key, "{ not json").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void anotherKeyForTheSameEnvironmentKeepsItsOwnAllowance() {
    String spent = issue().key();
    String other = issue().key();
    for (int i = 0; i < 4; i++) {
      config(spent, null);
    }

    assertThat(config(spent, null).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(config(other, null).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void theAllowanceComesBackWithTime() {
    String key = issue().key();
    for (int i = 0; i < 4; i++) {
      config(key, null);
    }

    clock.advance(Duration.ofSeconds(20));

    assertThat(config(key, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(config(key, null).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void revokingAKeyDropsItsBucketBeforeTheRevokeReturns() {
    Issued issued = issue();
    config(issued.key(), null);
    assertThat(limit.tracks(issued.id())).isTrue();

    ResponseEntity<String> revoked =
        api.exchange(
            HttpMethod.DELETE,
            "/api/projects/" + project + "/environments/production/keys/" + issued.id(),
            token,
            null);

    assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(limit.tracks(issued.id())).isFalse();
    assertThat(config(issued.key(), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(limit.tracks(issued.id())).as("a refused key is never charged").isFalse();
  }

  @Test
  void aRequestWithoutAKeyIsRefusedAsUnauthorisedNotLimited() {
    for (int i = 0; i < 5; i++) {
      assertThat(config("flg_srv_not-a-key", null).getStatusCode())
          .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
  }

  private record Issued(UUID id, String key) {}

  private Issued issue() {
    JsonNode issued =
        api.read(
            api.post(
                "/api/projects/" + project + "/environments/production/keys",
                token,
                Map.of("name", "limited", "type", "server")));
    return new Issued(UUID.fromString(issued.path("id").asText()), issued.path("key").asText());
  }

  private ResponseEntity<String> config(String key, String ifNoneMatch) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(key);
    if (ifNoneMatch != null) {
      headers.setIfNoneMatch(ifNoneMatch);
    }
    return http.exchange("/sdk/config", HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  private ResponseEntity<String> evaluate(String key, String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(key);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
    return http.exchange(
        "/sdk/evaluate", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }

  /** A clock that stands still until a test moves it, so no refill happens between requests. */
  static final class StoppedClock extends Clock {

    private volatile Instant now;

    StoppedClock(Instant start) {
      this.now = start;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
