package io.github.sanduniliyanage.flaglane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sanduniliyanage.flaglane.common.validation.ResourceKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The OpenAPI document and Swagger UI are served by the running application (NFR-MNT-002). */
@FlaglaneIntegrationTest
class OpenApiEndpointTest {

  private final TestRestTemplate http;
  private final ObjectMapper json;

  OpenApiEndpointTest(@Autowired TestRestTemplate http, @Autowired ObjectMapper json) {
    this.http = http;
    this.json = json;
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
  void documentDescribesTheAuthEndpointsAndTheBearerScheme() throws Exception {
    JsonNode body = json.readTree(http.getForEntity("/v3/api-docs", String.class).getBody());

    assertThat(body.path("paths").has("/api/auth/register")).isTrue();
    assertThat(body.path("paths").has("/api/auth/login")).isTrue();
    assertThat(body.at("/paths/~1api~1auth~1login/post/responses").has("401")).isTrue();
    assertThat(body.at("/components/securitySchemes/bearer-jwt/scheme").asText())
        .isEqualTo("bearer");
    assertThat(body.at("/components/securitySchemes/bearer-jwt/bearerFormat").asText())
        .isEqualTo("JWT");
  }

  @Test
  void tenantScopedPathsDeclareTheirVariablesAndNothingOfTheScopes() throws Exception {
    String document = http.getForEntity("/v3/api-docs", String.class).getBody();
    JsonNode parameters =
        json.readTree(document)
            .at("/paths/~1api~1projects~1{projectKey}~1environments~1{envKey}/delete/parameters");

    assertThat(parameters.findValuesAsText("name"))
        .containsExactlyInAnyOrder("projectKey", "envKey");
    assertThat(parameters.findValuesAsText("in")).containsOnly("path");
    assertThat(document).doesNotContain("OwnerScope", "ProjectScope", "EnvironmentScope");
  }

  @Test
  void everyOperationDocumentsItsOneSuccessResponseAsJson() throws Exception {
    JsonNode paths =
        json.readTree(http.getForEntity("/v3/api-docs", String.class).getBody()).path("paths");
    List<String> undocumented = new ArrayList<>();

    for (String path : names(paths)) {
      for (String method : names(paths.path(path))) {
        String name = method.toUpperCase(Locale.ROOT) + " " + path;
        JsonNode responses = paths.path(path).path(method).path("responses");
        List<String> success = names(responses).stream().filter(s -> s.startsWith("2")).toList();
        if (success.size() != 1) {
          undocumented.add(name + " documents " + success + " as success");
          continue;
        }
        JsonNode content = responses.path(success.getFirst()).path("content");
        if (!content.isMissingNode() && !names(content).equals(List.of("application/json"))) {
          undocumented.add(name + " answers " + names(content));
        }
      }
    }

    assertThat(undocumented).as("operations without one JSON success response").isEmpty();
  }

  @Test
  void aResponseRequiresEveryFieldItAlwaysSendsAndARequestOnlyWhatItNeeds() throws Exception {
    JsonNode schemas =
        json.readTree(http.getForEntity("/v3/api-docs", String.class).getBody())
            .path("components")
            .path("schemas");

    JsonNode response = schemas.path("FlagConfigResponse");
    List<String> required = new ArrayList<>();
    response.path("required").forEach(field -> required.add(field.asText()));
    assertThat(required).containsExactlyInAnyOrderElementsOf(names(response.path("properties")));
    assertThat(schemas.path("UpdateFlagConfigRequest").has("required")).isFalse();
  }

  @Test
  void aKeyInACreationIsDocumentedAsRequiredAndInItsFormat() throws Exception {
    JsonNode schemas =
        json.readTree(http.getForEntity("/v3/api-docs", String.class).getBody())
            .path("components")
            .path("schemas");

    for (String request :
        List.of("CreateProjectRequest", "CreateEnvironmentRequest", "CreateFlagRequest")) {
      List<String> required = new ArrayList<>();
      schemas.path(request).path("required").forEach(field -> required.add(field.asText()));
      assertThat(required).as(request).contains("key");
      assertThat(schemas.path(request).at("/properties/key/pattern").asText())
          .as(request)
          .isEqualTo(ResourceKey.REGEX);
    }
  }

  @Test
  void errorsAreDocumentedAsProblemDetails() throws Exception {
    JsonNode unauthorized =
        json.readTree(http.getForEntity("/v3/api-docs", String.class).getBody())
            .at("/paths/~1api~1auth~1login/post/responses/401/content");

    assertThat(names(unauthorized)).containsExactly("application/problem+json");
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

  private static List<String> names(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }
}
