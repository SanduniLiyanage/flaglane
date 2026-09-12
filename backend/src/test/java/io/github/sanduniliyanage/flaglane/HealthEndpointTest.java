package io.github.sanduniliyanage.flaglane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The health surface NFR-REL-003 specifies, against a real database with the two-role split. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {

  @Container private static final FlaglanePostgres POSTGRES = new FlaglanePostgres();

  private final TestRestTemplate http;
  private final ObjectMapper json;

  HealthEndpointTest(@Autowired TestRestTemplate http, @Autowired ObjectMapper json) {
    this.http = http;
    this.json = json;
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    POSTGRES.registerProperties(registry);
  }

  @Test
  void aggregateHealthReportsTheDatabaseAsAComponent() throws Exception {
    ResponseEntity<String> response = http.getForEntity("/actuator/health", String.class);

    JsonNode body = json.readTree(response.getBody());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(body.path("status").asText()).isEqualTo("UP");
    assertThat(body.path("components").path("db").path("status").asText()).isEqualTo("UP");
  }

  @Test
  void livenessIsUp() {
    ResponseEntity<String> response = http.getForEntity("/actuator/health/liveness", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void readinessIsUpAndDoesNotIncludeTheDatabase() throws Exception {
    ResponseEntity<String> response = http.getForEntity("/actuator/health/readiness", String.class);

    JsonNode body = json.readTree(response.getBody());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(body.path("status").asText()).isEqualTo("UP");
    assertThat(body.path("components").has("db")).isFalse();
  }
}
