package io.github.sanduniliyanage.flaglane.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import io.github.sanduniliyanage.flaglane.ManagementApiClient;
import io.github.sanduniliyanage.flaglane.streaming.EventStream.Event;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code GET /sdk/stream} end to end, over real connections (FR-SRV-003, FR-STR-001 to FR-STR-004):
 * a change made through the management API reaches the streams of its environment and no other, a
 * revoked key's streams end, and the ceilings hold. Timings are shortened so the suite does not
 * wait on them: a heartbeat every second, and streams that last six.
 */
@FlaglaneIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
    properties = {
      "flaglane.streaming.max-per-key=2",
      "flaglane.streaming.max-per-environment=3",
      "flaglane.streaming.heartbeat=PT1S",
      "flaglane.streaming.max-duration=PT6S"
    })
class StreamEndToEndTest {

  private static final Duration SOON = Duration.ofSeconds(5);

  private final ManagementApiClient api;
  private final TestRestTemplate http;
  private final ObjectMapper json;
  private final List<EventStream> opened = new ArrayList<>();

  @LocalServerPort private int port;

  private String token;
  private String project;

  StreamEndToEndTest(@Autowired TestRestTemplate http, @Autowired ObjectMapper json) {
    this.api = new ManagementApiClient(http, json);
    this.http = http;
    this.json = json;
  }

  @BeforeEach
  void aProjectWithAFlag() {
    token = api.signUp("streams");
    project = api.createProject(token, "streams");
    api.post("/api/projects/" + project + "/flags", token, Map.of("key", "checkout", "name", "C"));
  }

  @AfterEach
  void closeStreams() {
    opened.forEach(EventStream::close);
  }

  @Test
  void aStreamOpensWithTheVersionNowServed() throws Exception {
    String key = issue("production", "server").key();
    long served = servedVersion(key);

    EventStream stream = open(key);
    Event first = stream.nextEvent(SOON);

    assertThat(stream.status()).isEqualTo(200);
    assertThat(stream.header("Content-Type")).startsWith("text/event-stream");
    assertThat(stream.header("Cache-Control")).isEqualTo("private, no-store");
    assertThat(stream.header("X-Accel-Buffering")).isEqualTo("no");
    assertThat(first).isNotNull();
    assertThat(first.name()).isEqualTo("ruleset-changed");
    assertThat(first.id()).isEqualTo(Long.toString(served));
    assertThat(json.readTree(first.data()))
        .isEqualTo(json.readTree("{\"environment\":\"production\",\"version\":" + served + "}"));
  }

  @Test
  void aChangeReachesTheStreamsOfItsEnvironmentAndNoOther() throws Exception {
    EventStream production = open(issue("production", "server").key());
    EventStream staging = open(issue("staging", "server").key());
    long before = Long.parseLong(production.nextEvent(SOON).id());
    staging.nextEvent(SOON);

    turnOn("production");
    Event change = production.nextEvent(SOON);

    assertThat(change).isNotNull();
    assertThat(change.name()).isEqualTo("ruleset-changed");
    assertThat(Long.parseLong(change.id())).isGreaterThan(before);
    assertThat(staging.nextEvent(Duration.ofSeconds(2))).as("staging was not changed").isNull();
  }

  @Test
  void aClientKeyIsToldTheVersionAndNothingAboutTheFlags() throws Exception {
    EventStream stream = open(issue("production", "client").key());
    stream.nextEvent(SOON);

    turnOn("production");
    Event change = stream.nextEvent(SOON);

    assertThat(change).isNotNull();
    JsonNode data = json.readTree(change.data());
    assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("environment", "version");
    assertThat(change.data()).doesNotContain("checkout");
  }

  @Test
  void aQuietStreamGetsAHeartbeat() throws Exception {
    EventStream stream = open(issue("production", "server").key());

    assertThat(stream.heartbeat(Duration.ofSeconds(3))).isTrue();
  }

  @Test
  void revokingAKeyEndsItsStreamsAndRefusesNewOnes() throws Exception {
    Issued key = issue("production", "server");
    EventStream stream = open(key.key());
    stream.nextEvent(SOON);

    api.exchange(
        HttpMethod.DELETE,
        "/api/projects/" + project + "/environments/production/keys/" + key.id(),
        token,
        null);

    assertThat(stream.ends(Duration.ofSeconds(2))).isTrue();
    assertThat(open(key.key()).status()).isEqualTo(401);
  }

  @Test
  void aKeyHoldsAtMostItsCeilingAndAClosedStreamFreesItsPlace() throws Exception {
    String key = issue("production", "server").key();
    EventStream first = open(key);
    open(key);

    EventStream refused = open(key);

    assertThat(refused.status()).isEqualTo(429);
    assertThat(refused.header("Retry-After")).isEqualTo("60");
    assertThat(refused.errorBody(SOON)).contains("Too many open streams with this API key");

    first.close();
    assertThat(eventuallyOpens(key)).as("the closed stream's place is given back").isTrue();
  }

  @Test
  void anEnvironmentHoldsAtMostItsCeilingAcrossKeys() throws Exception {
    String one = issue("development", "server").key();
    String other = issue("development", "client").key();
    open(one);
    open(one);
    open(other);

    EventStream refused = open(other);

    assertThat(refused.status()).isEqualTo(429);
    assertThat(refused.errorBody(SOON)).contains("Too many open streams for this environment");
  }

  @Test
  void theServerEndsAStreamAfterItsMaximumDurationWithoutAnError(CapturedOutput output)
      throws Exception {
    EventStream stream = open(issue("production", "server").key());
    stream.nextEvent(SOON);

    assertThat(stream.ends(Duration.ofSeconds(10))).isTrue();
    Thread.sleep(500);
    assertThat(output.getAll())
        .doesNotContain("response is already committed")
        .doesNotContain("Unable to handle the Spring Security Exception")
        .doesNotContain("AsyncRequestNotUsableException");
  }

  private record Issued(String id, String key) {}

  private Issued issue(String environment, String type) {
    JsonNode created =
        api.read(
            api.post(
                "/api/projects/" + project + "/environments/" + environment + "/keys",
                token,
                Map.of("name", "stream-" + type, "type", type)));
    return new Issued(created.path("id").asText(), created.path("key").asText());
  }

  private EventStream open(String key) throws Exception {
    EventStream stream = EventStream.open(port, key);
    opened.add(stream);
    return stream;
  }

  /** Retries for a few seconds: the server learns of a closed connection at its next write. */
  private boolean eventuallyOpens(String key) throws Exception {
    long deadline = System.nanoTime() + SOON.toNanos();
    while (System.nanoTime() < deadline) {
      EventStream stream = open(key);
      if (stream.status() == 200) {
        return true;
      }
      stream.close();
      Thread.sleep(250);
    }
    return false;
  }

  private void turnOn(String environment) {
    ResponseEntity<String> changed =
        api.exchange(
            HttpMethod.PATCH,
            "/api/projects/" + project + "/flags/checkout/config/" + environment,
            token,
            Map.of("enabled", true));
    assertThat(changed.getStatusCode().is2xxSuccessful()).isTrue();
  }

  private long servedVersion(String key) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(key);
    ResponseEntity<String> config =
        http.exchange("/sdk/config", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    return json.readTree(config.getBody()).path("version").asLong();
  }
}
