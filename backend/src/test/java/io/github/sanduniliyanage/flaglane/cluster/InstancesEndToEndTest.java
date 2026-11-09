package io.github.sanduniliyanage.flaglane.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sanduniliyanage.flaglane.FlaglaneApplication;
import io.github.sanduniliyanage.flaglane.FlaglanePostgres;
import io.github.sanduniliyanage.flaglane.cluster.domain.InstanceId;
import io.github.sanduniliyanage.flaglane.streaming.EventStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Two instances of Flaglane against one database, each a whole application with its own port,
 * caches, streams and listening connection, as two replicas behind one address would be (ADR-041,
 * milestone 5's exit criteria). Everything one does is asked of the other: a key issued, a change
 * served, a stream told, a key revoked, and what one misses while its listening connection is cut.
 */
class InstancesEndToEndTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final Duration A_SECOND = Duration.ofSeconds(1);

  private static Instance first;
  private static Instance second;

  private final List<EventStream> streams = new ArrayList<>();
  private String token;
  private String project;

  private record Instance(ConfigurableApplicationContext context, int port) {

    InstanceId id() {
      return context.getBean(InstanceId.class);
    }
  }

  private record Answer(int status, JsonNode body) {}

  private record Key(String id, String key) {}

  @BeforeAll
  static void twoInstances() {
    first = start();
    second = start();
  }

  @AfterAll
  static void stopInstances() {
    if (first != null) {
      first.context().close();
    }
    if (second != null) {
      second.context().close();
    }
  }

  /** Command-line arguments, which outrank application.yml and the suite's own setting. */
  private static Instance start() {
    List<String> arguments = new ArrayList<>();
    FlaglanePostgres.shared()
        .connectionProperties()
        .forEach((name, value) -> arguments.add("--" + name + "=" + value));
    arguments.add("--server.port=0");
    arguments.add("--flaglane.instance-sync.enabled=true");
    arguments.add("--flaglane.streaming.heartbeat=PT1S");
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(FlaglaneApplication.class)
            .run(arguments.toArray(String[]::new));
    int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    return new Instance(context, port);
  }

  @BeforeEach
  void aProjectWithAFlagThroughTheFirst() throws Exception {
    String email = "instances-" + UUID.randomUUID() + "@example.com";
    Map<String, String> credentials = Map.of("email", email, "password", "correct-horse-battery");
    call(first, "POST", "/api/auth/register", null, credentials);
    token =
        call(first, "POST", "/api/auth/login", null, credentials)
            .body()
            .path("accessToken")
            .asText();
    project = "instances-" + UUID.randomUUID().toString().substring(0, 8);
    call(first, "POST", "/api/projects", token, Map.of("key", project, "name", "Instances"));
    call(
        first,
        "POST",
        "/api/projects/" + project + "/flags",
        token,
        Map.of("key", "checkout", "name", "C"));
  }

  @AfterEach
  void closeStreams() {
    streams.forEach(EventStream::close);
  }

  @Test
  void bothInstancesHearEachOther() throws Exception {
    for (Instance instance : List.of(first, second)) {
      JsonNode health = call(instance, "GET", "/actuator/health", null, null).body();
      assertThat(health.at("/components/instanceSync/status").asText()).isEqualTo("UP");
    }
  }

  @Test
  void aKeyIssuedThroughOneIsAcceptedByTheOtherWithinASecond() throws Exception {
    Key key = issue();

    assertThat(eventually(A_SECOND, () -> config(second, key).status() == 200)).isTrue();
  }

  @Test
  void aChangeThroughOneIsServedByTheOtherWithinASecond() throws Exception {
    Key key = issue();
    assertThat(eventually(A_SECOND, () -> config(second, key).status() == 200)).isTrue();
    long before = config(second, key).body().path("version").asLong();

    turnOn();

    assertThat(
            eventually(
                A_SECOND, () -> config(second, key).body().path("version").asLong() > before))
        .isTrue();
  }

  @Test
  void aStreamOnOneIsToldOfAChangeMadeThroughTheOther() throws Exception {
    Key key = issue();
    assertThat(eventually(A_SECOND, () -> config(second, key).status() == 200)).isTrue();
    EventStream stream = open(second, key);
    long before = Long.parseLong(stream.nextEvent(Duration.ofSeconds(5)).id());

    turnOn();
    EventStream.Event change = stream.nextEvent(Duration.ofSeconds(2));

    assertThat(change).isNotNull();
    assertThat(Long.parseLong(change.id())).isGreaterThan(before);
  }

  @Test
  void aKeyRevokedThroughOneIsRefusedByTheOtherAndItsStreamsThereEnd() throws Exception {
    Key key = issue();
    assertThat(eventually(A_SECOND, () -> config(second, key).status() == 200)).isTrue();
    EventStream stream = open(second, key);
    stream.nextEvent(Duration.ofSeconds(5));

    call(
        first,
        "DELETE",
        "/api/projects/" + project + "/environments/production/keys/" + key.id(),
        token,
        null);

    assertThat(stream.ends(Duration.ofSeconds(2))).isTrue();
    assertThat(eventually(A_SECOND, () -> config(second, key).status() == 401)).isTrue();
  }

  @Test
  void anInstanceWhoseListeningConnectionWasCutCatchesUpOnWhatItMissed() throws Exception {
    Key key = issue();
    assertThat(eventually(A_SECOND, () -> config(second, key).status() == 200)).isTrue();
    long before = config(second, key).body().path("version").asLong();

    assertThat(cutListener(second)).as("the second instance's listening connection").isEqualTo(1);
    turnOn();
    assertThat(listeners(second)).as("the change committed while it was not listening").isZero();

    assertThat(
            eventually(
                Duration.ofSeconds(10),
                () -> config(second, key).body().path("version").asLong() > before))
        .isTrue();
    assertThat(eventually(Duration.ofSeconds(5), () -> listeners(second) == 1)).isTrue();
  }

  private Key issue() throws Exception {
    JsonNode issued =
        call(
                first,
                "POST",
                "/api/projects/" + project + "/environments/production/keys",
                token,
                Map.of("name", "instances", "type", "server"))
            .body();
    return new Key(issued.path("id").asText(), issued.path("key").asText());
  }

  private void turnOn() throws Exception {
    Answer changed =
        call(
            first,
            "PATCH",
            "/api/projects/" + project + "/flags/checkout/config/production",
            token,
            Map.of("enabled", true));
    assertThat(changed.status()).isEqualTo(200);
  }

  private EventStream open(Instance instance, Key key) throws Exception {
    EventStream stream = EventStream.open(instance.port(), key.key());
    streams.add(stream);
    assertThat(stream.status()).isEqualTo(200);
    return stream;
  }

  private static Answer config(Instance instance, Key key) {
    try {
      HttpResponse<String> response =
          HTTP.send(
              HttpRequest.newBuilder(url(instance, "/sdk/config"))
                  .header("Authorization", "Bearer " + key.key())
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      return new Answer(response.statusCode(), parse(response.body()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Answer call(
      Instance instance, String method, String path, String token, Object body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(url(instance, path))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    HttpResponse<String> response =
        HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new Answer(response.statusCode(), parse(response.body()));
  }

  private static JsonNode parse(String body) {
    try {
      return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
    } catch (Exception e) {
      return JSON.createObjectNode();
    }
  }

  private static URI url(Instance instance, String path) {
    return URI.create("http://localhost:" + instance.port() + path);
  }

  /** Ends an instance's listening connection from the server's side, as a network fault would. */
  private static int cutListener(Instance instance) throws Exception {
    try (Connection database = FlaglanePostgres.shared().connectAsApp();
        PreparedStatement cut =
            database.prepareStatement(
                "select count(*) from (select pg_terminate_backend(pid) from pg_stat_activity"
                    + " where application_name = ?) terminated")) {
      cut.setString(1, listenerName(instance));
      try (ResultSet terminated = cut.executeQuery()) {
        terminated.next();
        return terminated.getInt(1);
      }
    }
  }

  private static int listeners(Instance instance) {
    try (Connection database = FlaglanePostgres.shared().connectAsApp();
        PreparedStatement count =
            database.prepareStatement(
                "select count(*) from pg_stat_activity where application_name = ?")) {
      count.setString(1, listenerName(instance));
      try (ResultSet listening = count.executeQuery()) {
        listening.next();
        return listening.getInt(1);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String listenerName(Instance instance) {
    return "flaglane-listener " + instance.id().shortForm();
  }

  /** Polls until the condition holds or the time is up; a condition that throws is not yet true. */
  private static boolean eventually(Duration within, BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + within.toNanos();
    while (System.nanoTime() < deadline) {
      try {
        if (condition.getAsBoolean()) {
          return true;
        }
      } catch (RuntimeException e) {
        // Not yet.
      }
      Thread.sleep(20);
    }
    return condition.getAsBoolean();
  }
}
