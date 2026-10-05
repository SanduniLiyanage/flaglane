package io.github.sanduniliyanage.flaglane.flag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import io.github.sanduniliyanage.flaglane.ManagementApiClient;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Flags and their configurations through the running application and a real database (FR-FLG-001 to
 * FR-FLG-007, FR-ENV-004), with the audit entries and ruleset versions each change writes.
 */
@FlaglaneIntegrationTest
class FlagsEndToEndTest {

  private final ManagementApiClient api;
  private final JdbcTemplate database;

  private String token;
  private String project;

  FlagsEndToEndTest(
      @Autowired TestRestTemplate http,
      @Autowired ObjectMapper json,
      @Autowired JdbcTemplate database) {
    this.api = new ManagementApiClient(http, json);
    this.database = database;
  }

  @BeforeEach
  void aProject() {
    token = api.signUp("flags");
    project = api.createProject(token, "flags");
  }

  @Test
  void aNewFlagIsDisabledAtZeroPercentInEveryEnvironment() {
    createFlag("new-checkout");

    for (String environment : List.of("development", "staging", "production")) {
      JsonNode config = api.read(api.get(config("new-checkout", environment), token));

      assertThat(config.path("enabled").asBoolean()).as(environment).isFalse();
      assertThat(config.path("fallthroughValue").asBoolean()).as(environment).isFalse();
      assertThat(config.path("rolloutPercentage").asInt()).as(environment).isZero();
      assertThat(config.path("rolloutSalt").asText()).as(environment).isEqualTo("new-checkout");
    }
  }

  @Test
  void aNewEnvironmentGetsAConfigurationForEveryLiveFlag() {
    createFlag("live");
    createFlag("retired");
    api.post(flags() + "/retired/archive", token, null);

    api.post(
        "/api/projects/" + project + "/environments", token, Map.of("key", "qa", "name", "QA"));

    assertThat(api.get(config("live", "qa"), token).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(api.get(config("retired", "qa"), token).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void aRestoredFlagIsConfiguredInEnvironmentsCreatedWhileItWasArchived() {
    createFlag("retired");
    api.post(flags() + "/retired/archive", token, null);
    api.post(
        "/api/projects/" + project + "/environments", token, Map.of("key", "qa", "name", "QA"));

    ResponseEntity<String> restored = api.post(flags() + "/retired/restore", token, null);

    assertThat(api.read(restored).path("archivedAt").isNull()).isTrue();
    assertThat(api.get(config("retired", "qa"), token).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void aConfigurationChangeIsStoredInBasisPointsAndAuditedAndChangesOneRuleset() {
    createFlag("checkout");
    long productionBefore = version("production");
    long stagingBefore = version("staging");

    ResponseEntity<String> changed =
        api.exchange(
            HttpMethod.PATCH,
            config("checkout", "production"),
            token,
            Map.of("enabled", true, "rolloutPercentage", 30));

    assertThat(api.read(changed).path("rolloutPercentage").asInt()).isEqualTo(30);
    assertThat(
            database.queryForObject(
                "select c.rollout_basis_points from flag_configs c join flags f on f.id = c.flag_id"
                    + " join environments e on e.id = c.environment_id join projects p"
                    + " on p.id = f.project_id where p.key = ? and f.key = 'checkout'"
                    + " and e.key = 'production'",
                Integer.class,
                project))
        .isEqualTo(3_000);
    assertThat(version("production")).isEqualTo(productionBefore + 1);
    assertThat(version("staging")).isEqualTo(stagingBefore);
    assertThat(auditedActions()).contains("config.updated");
  }

  @Test
  void anArchivedFlagCannotBeConfiguredUntilRestored() {
    createFlag("checkout");
    api.post(flags() + "/checkout/archive", token, null);

    assertThat(
            api.exchange(
                    HttpMethod.PATCH,
                    config("checkout", "production"),
                    token,
                    Map.of("enabled", true))
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void anArchivedFlagKeepsItsKeyReserved() {
    createFlag("checkout");
    api.post(flags() + "/checkout/archive", token, null);

    assertThat(api.post(flags(), token, Map.of("key", "checkout", "name", "Again")).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void theFlagLifecycleIsAuditedInOrder() {
    createFlag("checkout");
    api.exchange(HttpMethod.PATCH, flags() + "/checkout", token, Map.of("clientSideVisible", true));
    api.post(flags() + "/checkout/archive", token, null);
    api.post(flags() + "/checkout/restore", token, null);

    assertThat(auditedActions())
        .containsSubsequence("flag.created", "flag.updated", "flag.archived", "flag.restored");
  }

  @Test
  void theOffValueCannotBeSetThroughTheApi() {
    createFlag("checkout");

    ResponseEntity<String> attempt =
        api.exchange(
            HttpMethod.PATCH, config("checkout", "production"), token, Map.of("offValue", true));

    assertThat(api.read(attempt).path("offValue").asBoolean()).isFalse();
  }

  private void createFlag(String key) {
    ResponseEntity<String> created = api.post(flags(), token, Map.of("key", key, "name", key));
    assertThat(created.getStatusCode()).as("creating %s", key).isEqualTo(HttpStatus.CREATED);
  }

  private String flags() {
    return "/api/projects/" + project + "/flags";
  }

  private String config(String flag, String environment) {
    return flags() + "/" + flag + "/config/" + environment;
  }

  private long version(String environment) {
    return Objects.requireNonNull(
        database.queryForObject(
            "select e.ruleset_version from environments e join projects p on p.id = e.project_id"
                + " where p.key = ? and e.key = ?",
            Long.class,
            project,
            environment));
  }

  private List<String> auditedActions() {
    return database.queryForList(
        "select a.action from audit_entries a join projects p on p.id = a.project_id"
            + " where p.key = ? order by a.created_at, a.id",
        String.class,
        project);
  }
}
