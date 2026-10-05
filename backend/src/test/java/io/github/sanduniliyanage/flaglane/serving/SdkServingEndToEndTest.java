package io.github.sanduniliyanage.flaglane.serving;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import io.github.sanduniliyanage.flaglane.ManagementApiClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET /sdk/config} and {@code POST /sdk/evaluate} end to end: a project configured through
 * the management API, then read and evaluated with a server key and a client key (FR-SRV-001,
 * FR-SRV-002, FR-KEY-004, FR-KEY-005, FR-KEY-008, FR-EVL-007).
 */
@FlaglaneIntegrationTest
class SdkServingEndToEndTest {

  private final ManagementApiClient api;
  private final TestRestTemplate http;
  private final ObjectMapper json;

  private String token;
  private String project;
  private String serverKey;
  private String clientKey;

  SdkServingEndToEndTest(@Autowired TestRestTemplate http, @Autowired ObjectMapper json) {
    this.api = new ManagementApiClient(http, json);
    this.http = http;
    this.json = json;
  }

  /**
   * {@code banner}: client-visible, on for Sri Lanka by rule, with an override for one user. {@code
   * billing}: server-only, fully rolled out.
   */
  @BeforeEach
  void aConfiguredProjectAndTwoKeys() {
    token = api.signUp("serving");
    project = api.createProject(token, "serving");
    api.post(flags(), token, Map.of("key", "banner", "name", "Banner", "clientSideVisible", true));
    api.post(flags(), token, Map.of("key", "billing", "name", "Billing"));
    api.exchange(HttpMethod.PATCH, config("banner"), token, Map.of("enabled", true));
    api.exchange(
        HttpMethod.PUT,
        config("banner") + "/rules",
        token,
        Map.of(
            "rules",
            List.of(
                Map.of(
                    "attribute",
                    "country",
                    "operator",
                    "IN",
                    "matchValues",
                    List.of("LK"),
                    "resultValue",
                    true))));
    api.exchange(
        HttpMethod.PUT,
        config("banner") + "/overrides",
        token,
        Map.of("overrides", List.of(Map.of("userKey", "amara@example.com", "value", true))));
    api.exchange(
        HttpMethod.PATCH,
        config("billing"),
        token,
        Map.of("enabled", true, "rolloutPercentage", 100));
    serverKey = issue("server");
    clientKey = issue("client");
  }

  @Test
  void aServerKeyDownloadsEveryFlagWithAnEtagNamingItsType() throws Exception {
    ResponseEntity<String> response = config(serverKey, null);
    JsonNode body = json.readTree(response.getBody());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getHeaders().getETag())
        .isEqualTo("\"" + body.path("version").asLong() + "-server\"");
    assertThat(body.path("environment").asText()).isEqualTo("production");
    assertThat(body.path("flags")).hasSize(2);
    assertThat(body.path("flags").get(1).path("rolloutBasisPoints").asInt()).isEqualTo(10_000);
  }

  @Test
  void aClientKeyDownloadsVisibleFlagsOnlyWithNoOverrideAnywhere() throws Exception {
    ResponseEntity<String> response = config(clientKey, null);
    JsonNode body = json.readTree(response.getBody());

    assertThat(response.getHeaders().getETag()).endsWith("-client\"");
    assertThat(body.path("flags")).hasSize(1);
    assertThat(body.path("flags").get(0).path("key").asText()).isEqualTo("banner");
    assertThat(body.path("flags").get(0).has("overrides")).isFalse();
    assertThat(response.getBody()).doesNotContain("billing").doesNotContain("amara@example.com");
  }

  @Test
  void anUnchangedRulesetIsNotModified() {
    String etag = config(serverKey, null).getHeaders().getETag();

    ResponseEntity<String> again = config(serverKey, etag);

    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(again.getBody()).isNull();
    assertThat(again.getHeaders().getETag()).isEqualTo(etag);
  }

  @Test
  void aClientKeysEtagNeverValidatesAServerKeysRequest() {
    String clientEtag = config(clientKey, null).getHeaders().getETag();

    ResponseEntity<String> server = config(serverKey, clientEtag);

    assertThat(server.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(server.getBody()).contains("billing");
  }

  @Test
  void aChangeMakesTheOldEtagStale() {
    String before = config(serverKey, null).getHeaders().getETag();

    api.exchange(HttpMethod.PATCH, config("billing"), token, Map.of("rolloutPercentage", 50));
    ResponseEntity<String> after = config(serverKey, before);

    assertThat(after.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(after.getHeaders().getETag()).isNotEqualTo(before);
  }

  @Test
  void servingResponsesArePrivateAndVaryByKey() {
    ResponseEntity<String> response = config(serverKey, null);

    assertThat(response.getHeaders().getCacheControl()).isEqualTo("private, no-store");
    assertThat(response.getHeaders().getVary()).contains("Authorization");
  }

  @Test
  void evaluationAnswersWithAValueAndAReasonPerFlag() throws Exception {
    JsonNode results =
        evaluate(
                serverKey,
                Map.of("key", "u-1", "attributes", Map.of("country", "LK")),
                List.of(
                    Map.of("key", "banner", "fallback", false),
                    Map.of("key", "billing", "fallback", false),
                    Map.of("key", "spelled-wrong", "fallback", true)))
            .path("results");

    assertThat(results.path("banner").path("value").asBoolean()).isTrue();
    assertThat(results.path("banner").path("reason").asText()).isEqualTo("RULE_MATCH");
    assertThat(results.path("billing").path("reason").asText()).isEqualTo("ROLLOUT");
    assertThat(results.path("spelled-wrong").path("value").asBoolean()).isTrue();
    assertThat(results.path("spelled-wrong").path("reason").asText()).isEqualTo("FLAG_NOT_FOUND");
  }

  @Test
  void anOverrideAppliesToAServerKey() throws Exception {
    JsonNode result =
        evaluate(
                serverKey,
                Map.of("key", "amara@example.com"),
                List.of(Map.of("key", "banner", "fallback", false)))
            .path("results")
            .path("banner");

    assertThat(result.path("reason").asText()).isEqualTo("OVERRIDE");
  }

  @Test
  void anOverrideNeverAppliesToAClientKey() throws Exception {
    JsonNode result =
        evaluate(
                clientKey,
                Map.of("key", "amara@example.com"),
                List.of(Map.of("key", "banner", "fallback", false)))
            .path("results")
            .path("banner");

    assertThat(result.path("value").asBoolean()).isFalse();
    assertThat(result.path("reason").asText()).isEqualTo("FALLTHROUGH");
  }

  @Test
  void aFlagAClientKeyMayNotReadIsReportedAsNotFound() throws Exception {
    JsonNode result =
        evaluate(
                clientKey,
                Map.of("key", "u-1"),
                List.of(Map.of("key", "billing", "fallback", false)))
            .path("results")
            .path("billing");

    assertThat(result.path("value").asBoolean()).isFalse();
    assertThat(result.path("reason").asText()).isEqualTo("FLAG_NOT_FOUND");
  }

  @Test
  void anAnonymousEvaluationStillAppliesRules() throws Exception {
    JsonNode result =
        evaluate(
                clientKey,
                Map.of("attributes", Map.of("country", "LK")),
                List.of(Map.of("key", "banner", "fallback", false)))
            .path("results")
            .path("banner");

    assertThat(result.path("reason").asText()).isEqualTo("RULE_MATCH");
  }

  @Test
  void aFlagWithoutAFallbackIsABadRequest() {
    ResponseEntity<String> response =
        post(serverKey, Map.of("flags", List.of(Map.of("key", "banner"))));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void evaluationNeedsAKey() {
    assertThat(
            post(null, Map.of("flags", List.of(Map.of("key", "banner", "fallback", false))))
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private String issue(String type) {
    return api.read(
            api.post(
                "/api/projects/" + project + "/environments/production/keys",
                token,
                Map.of("name", type, "type", type)))
        .path("key")
        .asText();
  }

  private ResponseEntity<String> config(String key, String ifNoneMatch) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(key);
    if (ifNoneMatch != null) {
      headers.setIfNoneMatch(ifNoneMatch);
    }
    return http.exchange("/sdk/config", HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  private JsonNode evaluate(
      String key, Map<String, Object> context, List<Map<String, Object>> flags) throws Exception {
    ResponseEntity<String> response = post(key, Map.of("context", context, "flags", flags));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return json.readTree(response.getBody());
  }

  private ResponseEntity<String> post(String key, Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    if (key != null) {
      headers.setBearerAuth(key);
    }
    headers.setContentType(MediaType.APPLICATION_JSON);
    return http.exchange(
        "/sdk/evaluate", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }

  private String flags() {
    return "/api/projects/" + project + "/flags";
  }

  private String config(String flag) {
    return flags() + "/" + flag + "/config/production";
  }
}
