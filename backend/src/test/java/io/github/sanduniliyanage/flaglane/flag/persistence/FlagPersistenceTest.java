package io.github.sanduniliyanage.flaglane.flag.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantScopes;
import io.github.sanduniliyanage.flaglane.project.persistence.EnvironmentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/** Flags and their configurations as the application role, against the migrated schema. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(initializers = FlaglaneIntegrationTest.SharedDatabase.class)
@Import(TenantResolver.class)
class FlagPersistenceTest {

  private static final Instant NOW = Instant.parse("2026-10-05T09:30:00Z");

  private final FlagRepository flags;
  private final FlagConfigRepository configs;
  private final EnvironmentRepository environments;
  private final TenantResolver tenants;
  private final JdbcTemplate database;
  private final TestEntityManager entityManager;

  private ProjectScope alpha;
  private ProjectScope beta;
  private EnvironmentScope alphaProduction;

  FlagPersistenceTest(
      @Autowired FlagRepository flags,
      @Autowired FlagConfigRepository configs,
      @Autowired EnvironmentRepository environments,
      @Autowired TenantResolver tenants,
      @Autowired JdbcTemplate database,
      @Autowired TestEntityManager entityManager) {
    this.flags = flags;
    this.configs = configs;
    this.environments = environments;
    this.tenants = tenants;
    this.database = database;
    this.entityManager = entityManager;
  }

  @BeforeEach
  void twoProjects() {
    alpha = project("alpha");
    beta = project("beta");
    alphaProduction = tenants.environment(alpha, "production");
  }

  @Test
  void flagsAreListedForTheirOwnProjectOnly() {
    flag(alpha, "checkout");
    flag(beta, "search");

    assertThat(flags.findAll(alpha)).extracting(FlagEntity::getKey).containsExactly("checkout");
    assertThat(flags.find(alpha, "search")).isEmpty();
  }

  @Test
  void anArchivedFlagIsListedButIsNotLive() {
    flag(alpha, "live");
    flag(alpha, "retired").archive(NOW);
    entityManager.flush();

    assertThat(flags.findAll(alpha))
        .extracting(FlagEntity::getKey)
        .containsExactly("live", "retired");
    assertThat(flags.findLive(alpha)).extracting(FlagEntity::getKey).containsExactly("live");
  }

  @Test
  void anArchivedFlagKeepsItsKeyReserved() {
    flag(alpha, "retired").archive(NOW);
    entityManager.flush();

    assertThatExceptionOfType(DataIntegrityViolationException.class)
        .isThrownBy(() -> flag(alpha, "retired"));
  }

  @Test
  void theSameFlagKeyMayExistInTwoProjects() {
    flag(alpha, "checkout");

    assertThat(flag(beta, "checkout").getKey()).isEqualTo("checkout");
  }

  @Test
  void aConfigurationIsFoundByFlagKeyWithinTheEnvironmentsProjectOnly() {
    FlagEntity checkout = flag(alpha, "checkout");
    configs.saveAndFlush(
        FlagConfigEntity.newDefault(
            checkout.id(), "checkout", alphaProduction.environmentId(), NOW));
    flag(beta, "search");

    assertThat(configs.find(alphaProduction, "checkout")).isPresent();
    assertThat(configs.find(alphaProduction, "search")).isEmpty();
    assertThat(configs.find(tenants.environment(beta, "production"), "checkout")).isEmpty();
  }

  @Test
  void theOffValueIsFalseAndCannotBeWrittenThroughTheEntity() {
    FlagEntity checkout = flag(alpha, "checkout");
    FlagConfigEntity config =
        configs.saveAndFlush(
            FlagConfigEntity.newDefault(
                checkout.id(), "checkout", alphaProduction.environmentId(), NOW));
    entityManager.clear();

    assertThat(configs.find(alphaProduction, "checkout").orElseThrow().getOffValue()).isFalse();
    assertThat(
            database.queryForObject(
                "select off_value from flag_configs where id = ?", Boolean.class, config.id()))
        .isFalse();
  }

  @Test
  void aNewConfigurationIsSaltedWithItsFlagKey() {
    FlagEntity checkout = flag(alpha, "checkout");

    FlagConfigEntity config =
        configs.saveAndFlush(
            FlagConfigEntity.newDefault(
                checkout.id(), "checkout", alphaProduction.environmentId(), NOW));

    assertThat(config.getRolloutSalt()).isEqualTo("checkout");
    assertThat(config.isEnabled()).isFalse();
    assertThat(config.getRolloutBasisPoints()).isZero();
  }

  @Test
  void eachWriteToAConfigurationAdvancesItsRowVersion() {
    FlagEntity checkout = flag(alpha, "checkout");
    FlagConfigEntity config =
        configs.saveAndFlush(
            FlagConfigEntity.newDefault(
                checkout.id(), "checkout", alphaProduction.environmentId(), NOW));
    long before = config.getVersion();

    config.setEnabled(true);
    entityManager.flush();

    assertThat(config.getVersion()).isEqualTo(before + 1);
  }

  @Test
  void bumpingARulesetVersionTouchesOneEnvironmentOrTheWholeProject() {
    EnvironmentScope staging = tenants.environment(alpha, "staging");

    environments.bumpRulesetVersion(alphaProduction);
    environments.bumpRulesetVersions(alpha);

    assertThat(version(alphaProduction)).isEqualTo(2);
    assertThat(version(staging)).isEqualTo(1);
    assertThat(version(tenants.environment(beta, "production"))).isZero();
  }

  @Test
  void aProjectsEnvironmentsResolveAsScopes() {
    List<EnvironmentScope> scopes = tenants.environments(alpha);

    assertThat(scopes)
        .extracting(EnvironmentScope::environmentKey)
        .containsExactlyInAnyOrder("development", "staging", "production");
    assertThat(scopes).allSatisfy(scope -> assertThat(scope.project()).isEqualTo(alpha));
  }

  private long version(EnvironmentScope environment) {
    return Objects.requireNonNull(
        database.queryForObject(
            "select ruleset_version from environments where id = ?",
            Long.class,
            environment.environmentId()));
  }

  private FlagEntity flag(ProjectScope project, String key) {
    return flags.saveAndFlush(
        new FlagEntity(UUID.randomUUID(), project.projectId(), key, key, null, false, NOW));
  }

  private ProjectScope project(String prefix) {
    UUID user = UUID.randomUUID();
    UUID project = UUID.randomUUID();
    String key = prefix + "-" + project.toString().substring(0, 8);
    database.update(
        "insert into users (id, email, password_hash, created_at) values (?, ?, 'h', now())",
        user,
        user + "@example.com");
    database.update(
        "insert into projects (id, owner_id, key, name, created_at) values (?, ?, ?, 'P', now())",
        project,
        user,
        key);
    for (String environment : new String[] {"development", "staging", "production"}) {
      database.update(
          "insert into environments (id, project_id, key, name, created_at)"
              + " values (?, ?, ?, ?, now())",
          UUID.randomUUID(),
          project,
          environment,
          environment);
    }
    return tenants.project(TenantScopes.owner(user), key);
  }
}
