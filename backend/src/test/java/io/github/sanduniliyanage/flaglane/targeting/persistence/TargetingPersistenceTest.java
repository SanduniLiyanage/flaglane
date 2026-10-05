package io.github.sanduniliyanage.flaglane.targeting.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import io.github.sanduniliyanage.flaglane.audit.service.AuditLog;
import io.github.sanduniliyanage.flaglane.common.clock.ClockConfiguration;
import io.github.sanduniliyanage.flaglane.common.errors.ConflictException;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantScopes;
import io.github.sanduniliyanage.flaglane.project.service.RulesetVersions;
import io.github.sanduniliyanage.flaglane.targeting.domain.Rule;
import io.github.sanduniliyanage.flaglane.targeting.domain.UserOverride;
import io.github.sanduniliyanage.flaglane.targeting.service.TargetingService;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * Rule and override replacement against the real schema. FR-RUL-004's contiguity of priorities is
 * not expressible as a constraint, so it is asserted here, on the rows themselves.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(initializers = FlaglaneIntegrationTest.SharedDatabase.class)
@Import({
  TargetingService.class,
  TenantResolver.class,
  RulesetVersions.class,
  AuditLog.class,
  ClockConfiguration.class
})
class TargetingPersistenceTest {

  private final TargetingService targeting;
  private final TenantResolver tenants;
  private final JdbcTemplate database;
  private final TestEntityManager entityManager;

  private EnvironmentScope production;
  private EnvironmentScope staging;
  private UUID productionConfig;

  TargetingPersistenceTest(
      @Autowired TargetingService targeting,
      @Autowired TenantResolver tenants,
      @Autowired JdbcTemplate database,
      @Autowired TestEntityManager entityManager) {
    this.targeting = targeting;
    this.tenants = tenants;
    this.database = database;
    this.entityManager = entityManager;
  }

  @BeforeEach
  void aFlagConfiguredInTwoEnvironments() {
    UUID user = UUID.randomUUID();
    UUID project = UUID.randomUUID();
    UUID flag = UUID.randomUUID();
    String key = "targeting-" + project.toString().substring(0, 8);
    database.update(
        "insert into users (id, email, password_hash, created_at) values (?, ?, 'h', now())",
        user,
        user + "@example.com");
    database.update(
        "insert into projects (id, owner_id, key, name, created_at) values (?, ?, ?, 'P', now())",
        project,
        user,
        key);
    database.update(
        "insert into flags (id, project_id, key, name, client_side_visible, created_at)"
            + " values (?, ?, 'checkout', 'Checkout', false, now())",
        flag,
        project);
    for (String environment : new String[] {"production", "staging"}) {
      UUID environmentId = UUID.randomUUID();
      database.update(
          "insert into environments (id, project_id, key, name, created_at)"
              + " values (?, ?, ?, ?, now())",
          environmentId,
          project,
          environment,
          environment);
      UUID config = UUID.randomUUID();
      database.update(
          "insert into flag_configs (id, flag_id, environment_id, enabled, fallthrough_value,"
              + " rollout_basis_points, rollout_salt, updated_at)"
              + " values (?, ?, ?, true, false, 0, 'checkout', now())",
          config,
          flag,
          environmentId);
      if (environment.equals("production")) {
        productionConfig = config;
      }
    }
    ProjectScope scope = tenants.project(TenantScopes.owner(user), key);
    production = tenants.environment(scope, "production");
    staging = tenants.environment(scope, "staging");
  }

  @Test
  void prioritiesRunFromZeroWithNoGapsAfterEveryReplacement() {
    targeting.replaceRules(production, "checkout", rules(5));
    entityManager.flush();
    targeting.replaceRules(production, "checkout", rules(3));
    entityManager.flush();

    assertThat(priorities()).containsExactly(0, 1, 2);
  }

  @Test
  void replacingSwapsTheOrderWithoutEverHoldingTwoRulesAtOnePriority() {
    List<Rule> original = rules(3);
    targeting.replaceRules(production, "checkout", original);
    entityManager.flush();

    targeting.replaceRules(production, "checkout", original.reversed());
    entityManager.flush();

    assertThat(targeting.rules(production, "checkout"))
        .containsExactlyElementsOf(original.reversed());
    assertThat(priorities()).containsExactly(0, 1, 2);
  }

  @Test
  void matchValuesKeepTheirJsonTypes() {
    Rule mixed = new Rule("seats", "IN", List.of(1, 2, 3), true);
    targeting.replaceRules(production, "checkout", List.of(mixed));
    entityManager.flush();
    entityManager.clear();

    assertThat(
            database.queryForObject(
                "select match_values::text from targeting_rules where flag_config_id = ?",
                String.class,
                productionConfig))
        .isEqualTo("[1, 2, 3]");
    assertThat(targeting.rules(production, "checkout")).containsExactly(mixed);
  }

  @Test
  void replacingOneEnvironmentsRulesLeavesAnotherEnvironmentsAlone() {
    targeting.replaceRules(staging, "checkout", rules(2));
    targeting.replaceRules(production, "checkout", rules(1));
    entityManager.flush();

    targeting.replaceRules(production, "checkout", List.of());
    entityManager.flush();

    assertThat(targeting.rules(staging, "checkout")).hasSize(2);
    assertThat(targeting.rules(production, "checkout")).isEmpty();
  }

  @Test
  void overridesAreReplacedAsASet() {
    targeting.replaceOverrides(
        production,
        "checkout",
        List.of(new UserOverride("u-2", true), new UserOverride("u-1", false)));
    entityManager.flush();

    targeting.replaceOverrides(production, "checkout", List.of(new UserOverride("u-3", true)));
    entityManager.flush();

    assertThat(targeting.overrides(production, "checkout"))
        .containsExactly(new UserOverride("u-3", true));
  }

  @Test
  void replacementBumpsTheEnvironmentsRulesetVersionAndIsAudited() {
    targeting.replaceRules(production, "checkout", rules(1));
    entityManager.flush();

    assertThat(
            database.queryForObject(
                "select ruleset_version from environments where id = ?",
                Long.class,
                production.environmentId()))
        .isEqualTo(1);
    assertThat(
            database.queryForList(
                "select action from audit_entries where environment_id = ?",
                String.class,
                production.environmentId()))
        .containsExactly("rules.replaced");
  }

  @Test
  void anArchivedFlagsRulesCannotBeReplaced() {
    database.update(
        "update flags set archived_at = now() where key = 'checkout' and project_id = ?",
        production.projectId());

    assertThatExceptionOfType(ConflictException.class)
        .isThrownBy(() -> targeting.replaceRules(production, "checkout", rules(1)));
  }

  private List<Integer> priorities() {
    return database.queryForList(
        "select priority from targeting_rules where flag_config_id = ? order by priority",
        Integer.class,
        productionConfig);
  }

  private static List<Rule> rules(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> new Rule("plan", "EQUALS", List.of("plan-" + i), i % 2 == 0))
        .toList();
  }
}
