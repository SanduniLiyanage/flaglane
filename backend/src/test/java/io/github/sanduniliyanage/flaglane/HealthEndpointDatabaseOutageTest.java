package io.github.sanduniliyanage.flaglane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * NFR-REL-003: a database outage is visible on the aggregate endpoint and invisible to the liveness
 * and readiness probes, so an orchestrator never evicts an instance for it.
 *
 * <p>This test stops its database, so it owns a dedicated container rather than sharing one, and
 * starts and stops both the container and the application inside the test method so teardown runs
 * even when an assertion fails.
 */
class HealthEndpointDatabaseOutageTest {

  private final ObjectMapper json = new ObjectMapper();
  private final TestRestTemplate http = new TestRestTemplate();

  @Test
  void databaseOutageLeavesLivenessAndReadinessUp() throws Exception {
    try (FlaglanePostgres postgres = new FlaglanePostgres()) {
      postgres.start();

      try (ConfigurableApplicationContext application = start(postgres)) {
        String baseUrl =
            "http://localhost:" + application.getEnvironment().getProperty("local.server.port");
        assertThat(get(baseUrl, "/actuator/health").getStatusCode()).isEqualTo(HttpStatus.OK);

        postgres.stop();

        ResponseEntity<String> aggregate = get(baseUrl, "/actuator/health");
        JsonNode aggregateBody = json.readTree(aggregate.getBody());

        assertThat(get(baseUrl, "/actuator/health/liveness").getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(get(baseUrl, "/actuator/health/readiness").getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(aggregate.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(aggregateBody.path("components").path("db").path("status").asText())
            .isEqualTo("DOWN");
      }
    }
  }

  private static ConfigurableApplicationContext start(FlaglanePostgres postgres) {
    // Hikari waits 30 seconds for a connection by default; the outage should be noticed in one.
    Map<String, String> properties = new HashMap<>(postgres.connectionProperties());
    properties.put("server.port", "0");
    properties.put("spring.datasource.hikari.connection-timeout", "1000");
    properties.put("spring.datasource.hikari.connection-test-query", "SELECT 1");
    return new SpringApplicationBuilder(FlaglaneApplication.class)
        .initializers(context -> TestPropertyValues.of(properties).applyTo(context))
        .run();
  }

  private ResponseEntity<String> get(String baseUrl, String path) {
    return http.getForEntity(baseUrl + path, String.class);
  }
}
