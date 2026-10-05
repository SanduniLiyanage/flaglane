package io.github.sanduniliyanage.flaglane.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import io.github.sanduniliyanage.flaglane.ManagementApiClient;
import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKeyFormat;
import io.github.sanduniliyanage.flaglane.apikey.domain.KeyType;
import io.github.sanduniliyanage.flaglane.apikey.service.LastUsedRecorder;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * API keys through the running application and a real database (FR-KEY-001 to FR-KEY-007). No
 * {@code /sdk} endpoint exists before slice 2.8, so an unmapped {@code /sdk} path stands in for
 * one: 404 for a live key, 401 for anything else.
 */
@FlaglaneIntegrationTest
class ApiKeysEndToEndTest {

  private static final String SDK_PATH = "/sdk/not-yet";

  private final ManagementApiClient api;
  private final TestRestTemplate http;
  private final JdbcTemplate database;
  private final LastUsedRecorder lastUsed;

  private String token;
  private String keys;

  ApiKeysEndToEndTest(
      @Autowired TestRestTemplate http,
      @Autowired ObjectMapper json,
      @Autowired JdbcTemplate database,
      @Autowired LastUsedRecorder lastUsed) {
    this.api = new ManagementApiClient(http, json);
    this.http = http;
    this.database = database;
    this.lastUsed = lastUsed;
  }

  @BeforeEach
  void aProject() {
    token = api.signUp("keys");
    keys = "/api/projects/" + api.createProject(token, "keys") + "/environments/production/keys";
  }

  @Test
  void anIssuedKeyAuthenticatesOnTheServingApi() {
    String key = issue("server");

    ResponseEntity<String> served = sdk(key);

    assertThat(served.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void theKeyAppearsInOneResponseAndNowhereInTheDatabase() {
    String key = issue("client");

    String listed = api.get(keys, token).getBody();
    List<String> stored =
        database.queryForList(
            "select concat_ws(' ', k.key_hash, k.key_prefix, k.name, a.new_value::text)"
                + " from api_keys k join audit_entries a on a.environment_id = k.environment_id"
                + " where k.key_prefix = ?",
            String.class,
            ApiKeyFormat.prefix(key));

    assertThat(listed).doesNotContain(key);
    assertThat(stored).isNotEmpty().allSatisfy(row -> assertThat(row).doesNotContain(key));
    assertThat(
            database.queryForObject(
                "select key_hash from api_keys where key_prefix = ?",
                String.class,
                ApiKeyFormat.prefix(key)))
        .isEqualTo(ApiKeyFormat.hash(key));
  }

  @Test
  void aRevokedKeyIsRefusedFromTheMomentTheRevokeReturns() {
    String key = issue("server");
    UUID id = idOf(key);

    ResponseEntity<String> revoked = api.exchange(HttpMethod.DELETE, keys + "/" + id, token, null);

    assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(sdk(key).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(api.read(api.get(keys, token)).get(0).path("revokedAt").isNull()).isFalse();
  }

  @Test
  void issuingAndRevokingAreAudited() {
    String key = issue("server");
    api.exchange(HttpMethod.DELETE, keys + "/" + idOf(key), token, null);

    List<String> actions =
        database.queryForList(
            "select action from audit_entries where new_value->>'prefix' = ?"
                + " order by created_at",
            String.class,
            ApiKeyFormat.prefix(key));

    assertThat(actions).containsExactly("key.created", "key.revoked");
  }

  @Test
  void everyServingResponseIsPrivateAndVariesByAuthorization() {
    for (ResponseEntity<String> response : List.of(sdk(issue("server")), sdk(null))) {
      assertThat(response.getHeaders().getCacheControl())
          .as("Cache-Control on a %s", response.getStatusCode())
          .isEqualTo("private, no-store");
      assertThat(response.getHeaders().getVary())
          .as("Vary on a %s", response.getStatusCode())
          .contains("Authorization");
    }
  }

  @Test
  void aMissingMalformedOrUnknownKeyGetsOneAnswer() {
    String unknown = ApiKeyFormat.generate(KeyType.SERVER, new SecureRandom());

    for (String presented : new String[] {null, "garbage", unknown}) {
      ResponseEntity<String> response = sdk(presented);

      assertThat(response.getStatusCode())
          .as("presenting %s", presented)
          .isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(response.getBody())
          .as("presenting %s", presented)
          .contains("Missing, malformed or revoked API key");
    }
  }

  @Test
  void aDashboardTokenIsNotAnApiKeyAndAnApiKeyIsNotADashboardToken() {
    String key = issue("server");

    assertThat(sdk(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(api.get("/api/projects", key).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void lastUseIsRecordedByTheFlushNotByTheRequest() {
    String key = issue("server");
    UUID id = idOf(key);

    sdk(key);
    Instant beforeFlush = lastUsedAt(id);
    lastUsed.flush();

    assertThat(beforeFlush).isNull();
    assertThat(lastUsedAt(id)).isNotNull();
  }

  private String issue(String type) {
    ResponseEntity<String> issued = api.post(keys, token, Map.of("name", "k", "type", type));
    assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    JsonNode body = api.read(issued);
    return body.path("key").asText();
  }

  private UUID idOf(String key) {
    return database.queryForObject(
        "select id from api_keys where key_hash = ?", UUID.class, ApiKeyFormat.hash(key));
  }

  private Instant lastUsedAt(UUID id) {
    Timestamp at =
        database.queryForObject(
            "select last_used_at from api_keys where id = ?", Timestamp.class, id);
    return at == null ? null : at.toInstant();
  }

  private ResponseEntity<String> sdk(String key) {
    HttpHeaders headers = new HttpHeaders();
    if (key != null) {
      headers.setBearerAuth(key);
    }
    return http.exchange(SDK_PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }
}
