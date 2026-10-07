package io.github.sanduniliyanage.flaglane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * The dashboard's API types are generated from {@code dashboard/openapi.json}, a snapshot of the
 * document this application serves (ADR-031). This keeps the snapshot equal to the document, so a
 * change to an endpoint fails here until the snapshot, and the types made from it, are regenerated:
 *
 * <pre>
 * ./gradlew :backend:test --tests '*OpenApiSnapshotTest' -PupdateOpenApiSnapshot
 * npm --prefix dashboard run api:types
 * </pre>
 */
@FlaglaneIntegrationTest
class OpenApiSnapshotTest {

  private static final Path SNAPSHOT = Path.of("..", "dashboard", "openapi.json");

  private final TestRestTemplate http;
  private final ObjectMapper json;

  OpenApiSnapshotTest(@Autowired TestRestTemplate http, @Autowired ObjectMapper json) {
    this.http = http;
    this.json = json;
  }

  @Test
  void theDashboardsSnapshotIsTheDocumentServed() throws Exception {
    ObjectNode served = (ObjectNode) json.readTree(http.getForObject("/v3/api-docs", String.class));
    // The server URL names the test's random port; it is not part of the contract.
    served.remove("servers");

    if (Boolean.getBoolean("flaglane.openapi.update")) {
      Files.writeString(
          SNAPSHOT,
          json.writerWithDefaultPrettyPrinter().writeValueAsString(served) + "\n",
          StandardCharsets.UTF_8);
      return;
    }

    assertThat(SNAPSHOT).as("dashboard/openapi.json").exists();
    JsonNode snapshot = json.readTree(Files.readString(SNAPSHOT, StandardCharsets.UTF_8));
    assertThat(snapshot)
        .as(
            "dashboard/openapi.json is not the document the API serves. Regenerate it with"
                + " ./gradlew :backend:test --tests '*OpenApiSnapshotTest' -PupdateOpenApiSnapshot,"
                + " then npm --prefix dashboard run api:types")
        .isEqualTo(served);
  }
}
