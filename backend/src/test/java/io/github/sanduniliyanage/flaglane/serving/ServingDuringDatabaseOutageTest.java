package io.github.sanduniliyanage.flaglane.serving;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sanduniliyanage.flaglane.FlaglaneApplication;
import io.github.sanduniliyanage.flaglane.FlaglanePostgres;
import io.github.sanduniliyanage.flaglane.ManagementApiClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The product's central promise (docs/ARCHITECTURE.md section 7, NFR-REL-001, NFR-PER-004): with
 * the database gone, SDKs keep authenticating, downloading their ruleset and evaluating, from
 * memory, while the management API reports 503.
 *
 * <p>This test stops its database, so it owns a container and an application of its own.
 */
class ServingDuringDatabaseOutageTest {

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void servingCarriesOnWhenTheDatabaseIsDown() throws Exception {
    try (FlaglanePostgres postgres = new FlaglanePostgres()) {
      postgres.start();

      try (ConfigurableApplicationContext application = start(postgres)) {
        String baseUrl =
            "http://localhost:" + application.getEnvironment().getProperty("local.server.port");
        TestRestTemplate http = new TestRestTemplate(new RestTemplateBuilder().rootUri(baseUrl));
        ManagementApiClient api = new ManagementApiClient(http, json);
        String token = api.signUp("outage");
        String project = api.createProject(token, "outage");
        api.post(
            "/api/projects/" + project + "/flags", token, Map.of("key", "checkout", "name", "C"));
        api.exchange(
            HttpMethod.PATCH,
            "/api/projects/" + project + "/flags/checkout/config/production",
            token,
            Map.of("enabled", true, "fallthroughValue", true));
        String key =
            api.read(
                    api.post(
                        "/api/projects/" + project + "/environments/production/keys",
                        token,
                        Map.of("name", "k", "type", "server")))
                .path("key")
                .asText();

        postgres.stop();

        ResponseEntity<String> config = sdk(http, key, HttpMethod.GET, "/sdk/config", null);
        ResponseEntity<String> evaluated =
            sdk(
                http,
                key,
                HttpMethod.POST,
                "/sdk/evaluate",
                Map.of("flags", List.of(Map.of("key", "checkout", "fallback", false))));
        // The first management request may borrow a pooled connection that died with the
        // database; the driver reports only that it is closed, not why, so that one request is a
        // plain 500. Every request after it fails to get a connection at all, and is a 503.
        ResponseEntity<String> first = api.get("/api/projects", token);
        ResponseEntity<String> management = api.get("/api/projects", token);

        assertThat(config.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(config.getBody()).contains("checkout");
        assertThat(evaluated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(evaluated.getBody()).at("/results/checkout/value").asBoolean())
            .isTrue();
        assertThat(first.getStatusCode().is5xxServerError()).isTrue();
        assertThat(management.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      }
    }
  }

  private static ResponseEntity<String> sdk(
      TestRestTemplate http, String key, HttpMethod method, String path, Object body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(key);
    if (body != null) {
      headers.setContentType(MediaType.APPLICATION_JSON);
    }
    return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
  }

  private static ConfigurableApplicationContext start(FlaglanePostgres postgres) {
    // Hikari waits 30 seconds for a connection by default; the outage should be noticed in one.
    Map<String, String> properties = new HashMap<>(postgres.connectionProperties());
    properties.put("server.port", "0");
    properties.put("spring.datasource.hikari.connection-timeout", "1000");
    return new SpringApplicationBuilder(FlaglaneApplication.class)
        .initializers(context -> TestPropertyValues.of(properties).applyTo(context))
        .run();
  }
}
