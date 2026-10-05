package io.github.sanduniliyanage.flaglane.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import io.github.sanduniliyanage.flaglane.ManagementApiClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Projects and environments through the running application and a real database, with the audit
 * entries each change writes (FR-PRJ-001 to FR-ENV-004, FR-AUD-001).
 */
@FlaglaneIntegrationTest
class ProjectsEndToEndTest {

  private final ManagementApiClient api;
  private final JdbcTemplate database;

  ProjectsEndToEndTest(
      @Autowired TestRestTemplate http,
      @Autowired ObjectMapper json,
      @Autowired JdbcTemplate database) {
    this.api = new ManagementApiClient(http, json);
    this.database = database;
  }

  @Test
  void aNewProjectHasDevelopmentStagingAndProductionAndIsAudited() {
    String token = api.signUp("creator");
    String project = api.createProject(token, "storefront");

    JsonNode environments = api.read(api.get(environmentsOf(project), token));

    assertThat(keys(environments))
        .containsExactlyInAnyOrder("development", "staging", "production");
    assertThat(auditedActions(project)).containsExactly("project.created");
  }

  @Test
  void projectsAreListedForTheirOwnerOnly() {
    String alice = api.signUp("alice");
    String bob = api.signUp("bob");
    String alicesProject = api.createProject(alice, "alpha");
    api.createProject(bob, "beta");

    assertThat(keys(api.read(api.get("/api/projects", alice)))).containsExactly(alicesProject);
  }

  @Test
  void aProjectKeyTakenByAnyoneIsAConflict() {
    String taken = api.createProject(api.signUp("first"), "taken");

    assertThat(
            api.post("/api/projects", api.signUp("second"), Map.of("key", taken, "name", "Mine"))
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void anEnvironmentIsCreatedAndDeletedAndBothAreAudited() {
    String token = api.signUp("ops");
    String project = api.createProject(token, "ops");

    assertThat(
            api.post(environmentsOf(project), token, Map.of("key", "qa", "name", "QA"))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(
            api.exchange(HttpMethod.DELETE, environmentsOf(project) + "/qa", token, null)
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);

    assertThat(keys(api.read(api.get(environmentsOf(project), token)))).doesNotContain("qa");
    assertThat(auditedActions(project))
        .containsExactly("project.created", "environment.created", "environment.deleted");
  }

  @Test
  void anEnvironmentHoldingAnActiveKeyCannotBeDeleted() {
    String token = api.signUp("keyholder");
    String project = api.createProject(token, "keyed");
    UUID production =
        database.queryForObject(
            "select e.id from environments e join projects p on p.id = e.project_id"
                + " where p.key = ? and e.key = 'production'",
            UUID.class,
            project);
    database.update(
        "insert into api_keys (id, environment_id, key_hash, key_prefix, key_type, name,"
            + " created_at) values (?, ?, ?, 'flg_srv_abcdefgh', 'server', 'k', now())",
        UUID.randomUUID(),
        production,
        UUID.randomUUID().toString());

    assertThat(
            api.exchange(HttpMethod.DELETE, environmentsOf(project) + "/production", token, null)
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void aSecondEnvironmentWithTheSameKeyIsAConflict() {
    String token = api.signUp("dup");
    String project = api.createProject(token, "dup");

    assertThat(
            api.post(environmentsOf(project), token, Map.of("key", "staging", "name", "Again"))
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
  }

  private static String environmentsOf(String project) {
    return "/api/projects/" + project + "/environments";
  }

  private static List<String> keys(JsonNode array) {
    return StreamSupport.stream(array.spliterator(), false)
        .map(node -> node.path("key").asText())
        .toList();
  }

  private List<String> auditedActions(String project) {
    return database.queryForList(
        "select a.action from audit_entries a join projects p on p.id = a.project_id"
            + " where p.key = ? and a.actor_id = p.owner_id order by a.created_at, a.action",
        String.class,
        project);
  }
}
