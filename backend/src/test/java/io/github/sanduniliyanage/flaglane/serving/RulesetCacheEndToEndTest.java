package io.github.sanduniliyanage.flaglane.serving;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import io.github.sanduniliyanage.flaglane.ManagementApiClient;
import io.github.sanduniliyanage.flaglane.apikey.domain.KeyType;
import io.github.sanduniliyanage.flaglane.evaluation.FlagConfig;
import io.github.sanduniliyanage.flaglane.serving.domain.RulesetSnapshot;
import io.github.sanduniliyanage.flaglane.serving.service.RulesetCache;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The ruleset cache against real writes (ADR-008, NFR-PER-004): what it holds for each key type,
 * and that a change is in it by the time the request that made it returns.
 */
@FlaglaneIntegrationTest
class RulesetCacheEndToEndTest {

  private final ManagementApiClient api;
  private final ObjectMapper json;
  private final JdbcTemplate database;
  private final RulesetCache cache;

  private String token;
  private String project;
  private UUID production;

  RulesetCacheEndToEndTest(
      @Autowired TestRestTemplate http,
      @Autowired ObjectMapper json,
      @Autowired JdbcTemplate database,
      @Autowired RulesetCache cache) {
    this.api = new ManagementApiClient(http, json);
    this.json = json;
    this.database = database;
    this.cache = cache;
  }

  @BeforeEach
  void aProjectWithAVisibleAndAHiddenFlag() {
    token = api.signUp("cache");
    project = api.createProject(token, "cache");
    api.post(flags(), token, Map.of("key", "banner", "name", "Banner", "clientSideVisible", true));
    api.post(flags(), token, Map.of("key", "billing", "name", "Billing"));
    for (String flag : List.of("banner", "billing")) {
      api.exchange(
          HttpMethod.PUT,
          config(flag) + "/overrides",
          token,
          Map.of("overrides", List.of(Map.of("userKey", "amara@example.com", "value", true))));
      api.exchange(
          HttpMethod.PUT,
          config(flag) + "/rules",
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
    }
    production =
        database.queryForObject(
            "select e.id from environments e join projects p on p.id = e.project_id"
                + " where p.key = ? and e.key = 'production'",
            UUID.class,
            project);
  }

  @Test
  void aServerKeyGetsEveryLiveFlagWithItsRulesAndOverrides() throws Exception {
    RulesetSnapshot snapshot = snapshot();
    JsonNode body = json.readTree(snapshot.body(KeyType.SERVER));

    assertThat(keys(body)).containsExactly("banner", "billing");
    assertThat(body.path("flags").get(1).path("overrides").get(0).path("userKey").asText())
        .isEqualTo("amara@example.com");
    assertThat(body.path("flags").get(1).path("rules").get(0).path("operator").asText())
        .isEqualTo("IN");
    assertThat(snapshot.ruleset(KeyType.SERVER).flag("billing"))
        .get()
        .extracting(FlagConfig::overrides)
        .isEqualTo(Map.of("amara@example.com", true));
  }

  @Test
  void aClientKeyGetsVisibleFlagsOnlyAndNoOverrideAnywhere() throws Exception {
    RulesetSnapshot snapshot = snapshot();
    String body = snapshot.body(KeyType.CLIENT);

    assertThat(keys(json.readTree(body))).containsExactly("banner");
    assertThat(body).doesNotContain("billing").doesNotContain("amara@example.com");
    assertThat(json.readTree(body).path("flags").get(0).has("overrides")).isFalse();
    assertThat(json.readTree(body).path("flags").get(0).path("rules").size()).isEqualTo(1);
    assertThat(snapshot.ruleset(KeyType.CLIENT).flag("billing")).isEmpty();
    assertThat(snapshot.ruleset(KeyType.CLIENT).flag("banner"))
        .get()
        .extracting(FlagConfig::overrides)
        .isEqualTo(Map.of());
  }

  @Test
  void theSnapshotCarriesTheVersionItWasBuiltAt() {
    assertThat(snapshot().version()).isEqualTo(databaseVersion());
  }

  @Test
  void aChangeIsServedByTheTimeTheRequestThatMadeItReturns() {
    long before = snapshot().version();

    api.exchange(HttpMethod.PATCH, config("banner"), token, Map.of("enabled", true));

    assertThat(snapshot().version()).isEqualTo(before + 1).isEqualTo(databaseVersion());
    assertThat(snapshot().ruleset(KeyType.SERVER).flag("banner"))
        .get()
        .extracting(FlagConfig::enabled)
        .isEqualTo(true);
  }

  @Test
  void anArchivedFlagIsNotServed() {
    api.post(flags() + "/banner/archive", token, null);

    assertThat(snapshot().ruleset(KeyType.SERVER).flag("banner")).isEmpty();
    assertThat(snapshot().ruleset(KeyType.CLIENT).flag("banner")).isEmpty();
  }

  @Test
  void theEtagNamesTheVersionAndTheKeyType() {
    RulesetSnapshot snapshot = snapshot();

    assertThat(snapshot.etag(KeyType.SERVER)).isEqualTo("\"" + snapshot.version() + "-server\"");
    assertThat(snapshot.etag(KeyType.CLIENT)).isEqualTo("\"" + snapshot.version() + "-client\"");
  }

  @Test
  void aNewEnvironmentIsServedAndADeletedOneIsNot() {
    api.post(
        "/api/projects/" + project + "/environments", token, Map.of("key", "qa", "name", "QA"));
    UUID qa =
        database.queryForObject(
            "select e.id from environments e join projects p on p.id = e.project_id"
                + " where p.key = ? and e.key = 'qa'",
            UUID.class,
            project);

    assertThat(cache.get(qa))
        .get()
        .extracting(s -> s.ruleset(KeyType.SERVER).flags().size())
        .isEqualTo(2);

    api.exchange(HttpMethod.DELETE, "/api/projects/" + project + "/environments/qa", token, null);

    assertThat(cache.get(qa)).isEmpty();
  }

  private RulesetSnapshot snapshot() {
    return cache.get(production).orElseThrow();
  }

  private long databaseVersion() {
    return Objects.requireNonNull(
        database.queryForObject(
            "select ruleset_version from environments where id = ?", Long.class, production));
  }

  private String flags() {
    return "/api/projects/" + project + "/flags";
  }

  private String config(String flag) {
    return flags() + "/" + flag + "/config/production";
  }

  private static List<String> keys(JsonNode ruleset) {
    return StreamSupport.stream(ruleset.path("flags").spliterator(), false)
        .map(flag -> flag.path("key").asText())
        .toList();
  }
}
