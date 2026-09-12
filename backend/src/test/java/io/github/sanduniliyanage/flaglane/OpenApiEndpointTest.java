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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The OpenAPI document and Swagger UI are served by the running application (NFR-MNT-002). */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class OpenApiEndpointTest {

  @Container private static final FlaglanePostgres POSTGRES = new FlaglanePostgres();

  private final TestRestTemplate http;
  private final ObjectMapper json;

  OpenApiEndpointTest(@Autowired TestRestTemplate http, @Autowired ObjectMapper json) {
    this.http = http;
    this.json = json;
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    POSTGRES.registerProperties(registry);
  }

  @Test
  void openApiDocumentIsGeneratedFromSource() throws Exception {
    ResponseEntity<String> response = http.getForEntity("/v3/api-docs", String.class);

    JsonNode body = json.readTree(response.getBody());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType())
        .isNotNull()
        .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue());
    assertThat(body.path("openapi").asText()).startsWith("3.");
    assertThat(body.path("info").path("title").asText()).isEqualTo("Flaglane API");
  }

  @Test
  void swaggerUiIsLive() {
    ResponseEntity<String> response = http.getForEntity("/swagger-ui/index.html", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("swagger-ui");
  }

  @Test
  void swaggerUiShortcutReachesTheUi() {
    // /swagger-ui.html is a redirect to /swagger-ui/index.html. TestRestTemplate's default HTTP
    // client follows it, so the UI itself is what comes back.
    ResponseEntity<String> response = http.getForEntity("/swagger-ui.html", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("swagger-ui");
  }
}
