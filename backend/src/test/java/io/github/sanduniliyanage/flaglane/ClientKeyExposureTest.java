package io.github.sanduniliyanage.flaglane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
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
 * Suite 6 — client key exposure (FR-KEY-005, FR-KEY-008, NFR-SEC-002). A client key's ruleset
 * reaches every browser that loads the page, so what it must not contain is asserted on the
 * serialised response, not only on the fields a parser would read: a future field that carries a
 * hidden flag's rule or an override's user key is caught by the same search.
 *
 * <p>The environment holds a visible flag and a hidden one, each with rules and overrides, and
 * every string that must not escape is unique to this test.
 */
@FlaglaneIntegrationTest
class ClientKeyExposureTest {

  private final ManagementApiClient api;
  private final TestRestTemplate http;
  private final ObjectMapper json;

  private String token;
  private String project;
  private String clientKey;
  private String serverKey;

  private String visibleFlag;
  private String hiddenFlag;
  private String hiddenAttribute;
  private String hiddenMatchValue;
  private String hiddenSalt;
  private final List<String> overrideUserKeys = new ArrayList<>();

  ClientKeyExposureTest(@Autowired TestRestTemplate http, @Autowired ObjectMapper json) {
    this.api = new ManagementApiClient(http, json);
    this.http = http;
    this.json = json;
  }

  @BeforeEach
  void anEnvironmentWithAVisibleAndAHiddenFlag() {
    String run = UUID.randomUUID().toString().substring(0, 8);
    token = api.signUp("exposure");
    project = api.createProject(token, "exposure");
    visibleFlag = "visible-" + run;
    hiddenFlag = "hidden-" + run;
    hiddenAttribute = "hidden-attribute-" + run;
    hiddenMatchValue = "hidden-match-value-" + run;
    hiddenSalt = "hidden-salt-" + run;

    created(
        api.post(
            flags(),
            token,
            Map.of("key", visibleFlag, "name", "Visible", "clientSideVisible", true)));
    created(api.post(flags(), token, Map.of("key", hiddenFlag, "name", "Hidden")));

    configure(visibleFlag, "plan", "pro", null);
    configure(hiddenFlag, hiddenAttribute, hiddenMatchValue, hiddenSalt);

    // Overrides on both flags, including user keys a naive search would miss: non-ASCII, and one
    // that JSON has to escape.
    for (String flag : List.of(visibleFlag, hiddenFlag)) {
      List<String> users =
          List.of("override-" + flag + "@example.com", "සඳුනි-" + flag, "quote\"" + flag);
      overrideUserKeys.addAll(users);
      ok(
          api.exchange(
              HttpMethod.PUT,
              config(flag) + "/overrides",
              token,
              Map.of(
                  "overrides",
                  users.stream().map(user -> Map.of("userKey", user, "value", true)).toList())));
    }

    clientKey = issue("client");
    serverKey = issue("server");
  }

  @Test
  void aClientKeyReceivesOnlyClientSideVisibleFlags() throws Exception {
    JsonNode body = json.readTree(config(clientKey, null).getBody());

    assertThat(StreamSupport.stream(body.path("flags").spliterator(), false))
        .extracting(flag -> flag.path("key").asText())
        .containsExactly(visibleFlag);
  }

  @Test
  void nothingOfAHiddenFlagAppearsAnywhereInAClientKeysPayload() {
    String body = config(clientKey, null).getBody();

    assertThat(body)
        .as("client payload")
        .doesNotContain(hiddenFlag)
        .doesNotContain(hiddenAttribute)
        .doesNotContain(hiddenMatchValue)
        .doesNotContain(hiddenSalt);
  }

  @Test
  void noOverrideUserKeyOfAnyFlagAppearsAnywhereInAClientKeysPayload() throws Exception {
    String body = config(clientKey, null).getBody();

    for (String userKey : overrideUserKeys) {
      String escaped = json.writeValueAsString(userKey);
      String escapedWithoutQuotes = escaped.substring(1, escaped.length() - 1);
      assertThat(body).as("client payload, searched for %s", userKey).doesNotContain(userKey);
      assertThat(body)
          .as("client payload, searched for %s as JSON writes it", userKey)
          .doesNotContain(escapedWithoutQuotes);
    }
  }

  @Test
  void aClientKeysPayloadHasNoOverridesFieldOnAnyFlag() throws Exception {
    JsonNode body = json.readTree(config(clientKey, null).getBody());

    assertThat(StreamSupport.stream(body.path("flags").spliterator(), false))
        .allSatisfy(
            flag -> assertThat(flag.has("overrides")).as(flag.path("key").asText()).isFalse());
  }

  @Test
  void theSameEnvironmentServesAServerKeyEverything() {
    String body = config(serverKey, null).getBody();

    assertThat(body).contains(hiddenFlag).contains(hiddenMatchValue);
    assertThat(body).contains("override-" + hiddenFlag + "@example.com");
  }

  @Test
  void aClientKeysEtagDoesNotValidateAServerKeysRequest() {
    String clientEtag = config(clientKey, null).getHeaders().getETag();

    ResponseEntity<String> server = config(serverKey, clientEtag);

    assertThat(server.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(server.getBody()).contains(hiddenFlag);
  }

  @Test
  void aServerKeysEtagDoesNotValidateAClientKeysRequest() {
    String serverEtag = config(serverKey, null).getHeaders().getETag();

    ResponseEntity<String> client = config(clientKey, serverEtag);

    assertThat(client.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(client.getBody()).doesNotContain(hiddenFlag);
  }

  @Test
  void aClientKeysResponseIsPrivateAndVariesByAuthorization() {
    ResponseEntity<String> response = config(clientKey, null);

    assertThat(response.getHeaders().getCacheControl()).isEqualTo("private, no-store");
    assertThat(response.getHeaders().getVary()).contains("Authorization");
  }

  @Test
  void serverSideEvaluationWithAClientKeyNeitherRevealsNorAppliesWhatIsHidden() throws Exception {
    String overriddenUser = "override-" + visibleFlag + "@example.com";

    ResponseEntity<String> response =
        evaluate(
            clientKey,
            Map.of(
                "context",
                Map.of(
                    "key", overriddenUser, "attributes", Map.of(hiddenAttribute, hiddenMatchValue)),
                "flags",
                List.of(
                    Map.of("key", visibleFlag, "fallback", false),
                    Map.of("key", hiddenFlag, "fallback", false))));
    JsonNode results = json.readTree(response.getBody()).path("results");

    assertThat(results.path(visibleFlag).path("reason").asText())
        .as("an override for a client key")
        .isNotEqualTo("OVERRIDE");
    assertThat(results.path(hiddenFlag).path("reason").asText()).isEqualTo("FLAG_NOT_FOUND");
    assertThat(results.path(hiddenFlag).path("value").asBoolean()).isFalse();
  }

  @Test
  void aKeyServesItsOwnEnvironmentAndNoOther() throws Exception {
    String otherToken = api.signUp("other");
    String otherProject = api.createProject(otherToken, "other");
    String otherFlag = "theirs-" + UUID.randomUUID().toString().substring(0, 8);
    created(
        api.post(
            "/api/projects/" + otherProject + "/flags",
            otherToken,
            Map.of("key", otherFlag, "name", "Theirs", "clientSideVisible", true)));

    JsonNode stagingOfThisProject =
        json.readTree(
            config(
                    api.read(
                            api.post(
                                "/api/projects/" + project + "/environments/staging/keys",
                                token,
                                Map.of("name", "staging", "type", "server")))
                        .path("key")
                        .asText(),
                    null)
                .getBody());

    assertThat(config(serverKey, null).getBody()).doesNotContain(otherFlag);
    assertThat(config(clientKey, null).getBody()).doesNotContain(otherFlag);
    assertThat(stagingOfThisProject.path("environment").asText()).isEqualTo("staging");
    assertThat(json.readTree(config(serverKey, null).getBody()).path("environment").asText())
        .isEqualTo("production");
  }

  private void configure(String flag, String attribute, String matchValue, String salt) {
    Map<String, Object> change =
        salt == null
            ? Map.of("enabled", true, "rolloutPercentage", 50)
            : Map.of("enabled", true, "rolloutPercentage", 50, "rolloutSalt", salt);
    ok(api.exchange(HttpMethod.PATCH, config(flag), token, change));
    ok(
        api.exchange(
            HttpMethod.PUT,
            config(flag) + "/rules",
            token,
            Map.of(
                "rules",
                List.of(
                    Map.of(
                        "attribute",
                        attribute,
                        "operator",
                        "EQUALS",
                        "matchValues",
                        List.of(matchValue),
                        "resultValue",
                        true)))));
  }

  private String issue(String type) {
    ResponseEntity<String> issued =
        api.post(
            "/api/projects/" + project + "/environments/production/keys",
            token,
            Map.of("name", type, "type", type));
    created(issued);
    return api.read(issued).path("key").asText();
  }

  private ResponseEntity<String> config(String key, String ifNoneMatch) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(key);
    if (ifNoneMatch != null) {
      headers.setIfNoneMatch(ifNoneMatch);
    }
    return http.exchange("/sdk/config", HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  private ResponseEntity<String> evaluate(String key, Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(key);
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

  private static void created(ResponseEntity<String> response) {
    assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
  }

  private static void ok(ResponseEntity<String> response) {
    assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
  }
}
