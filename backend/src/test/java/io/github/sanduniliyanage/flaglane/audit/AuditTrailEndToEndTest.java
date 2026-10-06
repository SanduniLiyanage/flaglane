package io.github.sanduniliyanage.flaglane.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import io.github.sanduniliyanage.flaglane.ManagementApiClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The audit trail through the running application (FR-AUD-003): changes made over the management
 * API come back newest first, named by key, narrowed by environment or flag, and paged without
 * repeats or gaps.
 */
@FlaglaneIntegrationTest
class AuditTrailEndToEndTest {

  private final ManagementApiClient api;

  private String token;
  private String project;
  private String audit;
  private String issuedKey;

  AuditTrailEndToEndTest(@Autowired TestRestTemplate http, @Autowired ObjectMapper json) {
    this.api = new ManagementApiClient(http, json);
  }

  @BeforeEach
  void aProjectWithAHistory() {
    token = api.signUp("auditor");
    project = api.createProject(token, "audited");
    audit = "/api/projects/" + project + "/audit";
    String base = "/api/projects/" + project;
    ok(api.post(base + "/flags", token, Map.of("key", "checkout", "name", "Checkout")));
    ok(
        api.exchange(
            HttpMethod.PATCH,
            base + "/flags/checkout/config/production",
            token,
            Map.of("enabled", true, "rolloutPercentage", 30)));
    ok(api.post(base + "/environments", token, Map.of("key", "qa", "name", "QA")));
    JsonNode key =
        api.read(
            ok(
                api.post(
                    base + "/environments/qa/keys",
                    token,
                    Map.of("name", "ci", "type", "server"))));
    issuedKey = key.path("key").asText();
    ok(
        api.exchange(
            HttpMethod.DELETE,
            base + "/environments/qa/keys/" + key.path("id").asText(),
            token,
            null));
    ok(api.exchange(HttpMethod.DELETE, base + "/environments/qa", token, null));
  }

  @Test
  void changesComeBackNewestFirstNamedByKey() {
    List<JsonNode> entries = all("");

    assertThat(entries)
        .extracting(entry -> entry.path("action").asText())
        .startsWith(
            "environment.deleted",
            "key.revoked",
            "key.created",
            "environment.created",
            "config.updated",
            "flag.created")
        .endsWith("project.created");
    JsonNode config = entries.get(4);
    assertThat(config.path("environment").asText()).isEqualTo("production");
    assertThat(config.path("flag").asText()).isEqualTo("checkout");
    assertThat(config.path("actor").path("email").asText()).startsWith("auditor-");
    assertThat(config.path("previousValue").path("enabled").asBoolean()).isFalse();
    assertThat(config.path("newValue").path("enabled").asBoolean()).isTrue();
    assertThat(config.path("newValue").path("rolloutBasisPoints").asInt()).isEqualTo(3000);
  }

  @Test
  void aDeletedEnvironmentsEntriesStillNameItAndSaySoItIsGone() {
    List<JsonNode> qa = all("").subList(0, 4);

    assertThat(qa)
        .allSatisfy(
            entry -> {
              assertThat(entry.path("environment").asText()).isEqualTo("qa");
              assertThat(entry.path("environmentDeleted").asBoolean()).isTrue();
            });
  }

  @Test
  void theTrailNeverCarriesAnApiKey() {
    ResponseEntity<String> page = api.get(audit + "?limit=100", token);

    assertThat(page.getBody()).doesNotContain(issuedKey);
    assertThat(issuedKey).hasSizeGreaterThan(20);
  }

  @Test
  void aPageCanBeNarrowedToAFlagOrAnEnvironment() {
    List<JsonNode> checkout = all("&flag=checkout");
    List<JsonNode> production = all("&environment=production");

    assertThat(checkout)
        .extracting(entry -> entry.path("action").asText())
        .containsExactly("config.updated", "flag.created");
    assertThat(production)
        .isNotEmpty()
        .allSatisfy(
            entry -> assertThat(entry.path("environment").asText()).isEqualTo("production"));
  }

  @Test
  void pagingTwoAtATimeReturnsTheSameEntriesAsOnePage() {
    // The client encodes the query itself; the cursor goes in as the API returned it.
    List<String> paged = new ArrayList<>();
    String next = null;
    do {
      String query = "?limit=2" + (next == null ? "" : "&before=" + next);
      JsonNode page = api.read(ok(api.get(audit + query, token)));
      page.path("entries").forEach(entry -> paged.add(entry.path("id").asText()));
      next = page.path("next").isNull() ? null : page.path("next").asText();
    } while (next != null);

    assertThat(paged)
        .containsExactlyElementsOf(all("").stream().map(e -> e.path("id").asText()).toList());
  }

  @Test
  void unknownFiltersAreNotFoundAndMalformedOnesBadRequests() {
    assertThat(api.get(audit + "?environment=qa", token).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(api.get(audit + "?flag=nope", token).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(api.get(audit + "?before=yesterday", token).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(api.get(audit + "?limit=101", token).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void anotherUsersTrailIsNotFound() {
    String stranger = api.signUp("stranger");

    assertThat(api.get(audit, stranger).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  private List<JsonNode> all(String filters) {
    JsonNode page = api.read(ok(api.get(audit + "?limit=100" + filters, token)));
    assertThat(page.path("next").isNull()).as("one page holds this project's trail").isTrue();
    List<JsonNode> entries = new ArrayList<>();
    page.path("entries").forEach(entries::add);
    return entries;
  }

  private static ResponseEntity<String> ok(ResponseEntity<String> response) {
    assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
    return response;
  }
}
