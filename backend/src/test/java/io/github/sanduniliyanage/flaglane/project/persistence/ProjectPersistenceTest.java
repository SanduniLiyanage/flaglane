package io.github.sanduniliyanage.flaglane.project.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import io.github.sanduniliyanage.flaglane.account.persistence.UserEntity;
import io.github.sanduniliyanage.flaglane.account.persistence.UserRepository;
import io.github.sanduniliyanage.flaglane.common.errors.NotFoundException;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.OwnerScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantScopes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * Projects and environments as the application role, against the migrated schema. Every read goes
 * through a scope, and a scope is only ever obtained from {@link TenantResolver}, as in the
 * application.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(initializers = FlaglaneIntegrationTest.SharedDatabase.class)
@Import(TenantResolver.class)
class ProjectPersistenceTest {

  private static final Instant NOW = Instant.parse("2026-10-05T09:30:00.123456Z");

  private final ProjectRepository projects;
  private final EnvironmentRepository environments;
  private final UserRepository users;
  private final TenantResolver tenants;
  private final JdbcTemplate database;

  private OwnerScope alice;
  private OwnerScope bob;

  ProjectPersistenceTest(
      @Autowired ProjectRepository projects,
      @Autowired EnvironmentRepository environments,
      @Autowired UserRepository users,
      @Autowired TenantResolver tenants,
      @Autowired JdbcTemplate database) {
    this.projects = projects;
    this.environments = environments;
    this.users = users;
    this.tenants = tenants;
    this.database = database;
  }

  @BeforeEach
  void twoUsers() {
    alice = TenantScopes.owner(user("alice"));
    bob = TenantScopes.owner(user("bob"));
  }

  @Test
  void aUserSeesOnlyTheirOwnProjects() {
    String alpha = project(alice, "alpha");
    project(bob, "beta");

    List<ProjectEntity> found = projects.findAll(alice);

    assertThat(found).extracting(ProjectEntity::getKey).containsExactly(alpha);
  }

  @Test
  void anotherUsersProjectIsNotFoundExactlyLikeAMissingOne() {
    String beta = project(bob, "beta");

    assertThatExceptionOfType(NotFoundException.class)
        .isThrownBy(() -> tenants.project(alice, beta))
        .withMessage("Project not found");
    assertThatExceptionOfType(NotFoundException.class)
        .isThrownBy(() -> tenants.project(alice, "no-such-project"))
        .withMessage("Project not found");
  }

  @Test
  void aResolvedProjectScopeFindsItsProject() {
    String alpha = project(alice, "alpha");

    ProjectScope scope = tenants.project(alice, alpha);

    assertThat(projects.find(scope)).get().extracting(ProjectEntity::getKey).isEqualTo(alpha);
    assertThat(scope.userId()).isEqualTo(alice.userId());
  }

  @Test
  void anEnvironmentIsOnlyFoundInsideItsOwnProject() {
    ProjectScope alpha = tenants.project(alice, project(alice, "alpha"));
    ProjectScope beta = tenants.project(bob, project(bob, "beta"));
    environment(beta, "beta-only");

    assertThatExceptionOfType(NotFoundException.class)
        .isThrownBy(() -> tenants.environment(alpha, "beta-only"))
        .withMessage("Environment not found");
    assertThat(tenants.environment(beta, "beta-only").environmentKey()).isEqualTo("beta-only");
  }

  @Test
  void environmentsAreListedForOneProjectOnly() {
    ProjectScope alpha = tenants.project(alice, project(alice, "alpha"));
    ProjectScope beta = tenants.project(bob, project(bob, "beta"));
    environment(alpha, "qa");
    environment(beta, "uat");

    assertThat(environments.findAll(alpha))
        .extracting(EnvironmentEntity::getKey)
        .containsExactly("qa");
  }

  @Test
  void aProjectKeyIsUniqueAcrossEveryOwner() {
    String key = project(alice, "taken");

    assertThatExceptionOfType(DataIntegrityViolationException.class)
        .isThrownBy(
            () ->
                projects.saveAndFlush(
                    new ProjectEntity(UUID.randomUUID(), bob.userId(), key, "Mine now", NOW)));
  }

  @Test
  void anEnvironmentKeyIsUniqueWithinItsProjectOnly() {
    ProjectScope alpha = tenants.project(alice, project(alice, "alpha"));
    ProjectScope beta = tenants.project(bob, project(bob, "beta"));
    environment(alpha, "qa");
    environment(beta, "qa");

    assertThatExceptionOfType(DataIntegrityViolationException.class)
        .isThrownBy(() -> environment(alpha, "qa"));
  }

  @Test
  void onlyKeysNotYetRevokedCountAsActive() {
    ProjectScope alpha = tenants.project(alice, project(alice, "alpha"));
    EnvironmentScope qa = environment(alpha, "qa");
    key(qa, null);
    key(qa, NOW);

    assertThat(environments.countActiveKeys(qa)).isEqualTo(1);
  }

  @Test
  void deletingAnEnvironmentTakesItsConfigurationsWithIt() {
    ProjectScope alpha = tenants.project(alice, project(alice, "alpha"));
    EnvironmentScope qa = environment(alpha, "qa");
    UUID flag = UUID.randomUUID();
    database.update(
        "insert into flags (id, project_id, key, name, client_side_visible, created_at)"
            + " values (?, ?, 'checkout', 'Checkout', false, now())",
        flag,
        alpha.projectId());
    database.update(
        "insert into flag_configs (id, flag_id, environment_id, enabled, fallthrough_value,"
            + " rollout_basis_points, rollout_salt, updated_at)"
            + " values (?, ?, ?, false, false, 0, 'checkout', now())",
        UUID.randomUUID(),
        flag,
        qa.environmentId());

    int deleted = environments.delete(qa);

    assertThat(deleted).isEqualTo(1);
    assertThat(environments.find(qa)).isEmpty();
    assertThat(
            database.queryForObject(
                "select count(*) from flag_configs where environment_id = ?",
                Long.class,
                qa.environmentId()))
        .isZero();
  }

  private UUID user(String name) {
    UUID id = UUID.randomUUID();
    users.saveAndFlush(new UserEntity(id, name + "-" + id + "@example.com", "h", null, NOW));
    return id;
  }

  private String project(OwnerScope owner, String prefix) {
    String key = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    projects.saveAndFlush(new ProjectEntity(UUID.randomUUID(), owner.userId(), key, prefix, NOW));
    return key;
  }

  private EnvironmentScope environment(ProjectScope project, String key) {
    environments.saveAndFlush(
        new EnvironmentEntity(UUID.randomUUID(), project.projectId(), key, key, NOW));
    return tenants.environment(project, key);
  }

  private void key(EnvironmentScope environment, Instant revokedAt) {
    database.update(
        "insert into api_keys (id, environment_id, key_hash, key_prefix, key_type, name,"
            + " revoked_at, created_at) values (?, ?, ?, 'flg_srv_abcdefgh', 'server', 'k', ?,"
            + " now())",
        UUID.randomUUID(),
        environment.environmentId(),
        UUID.randomUUID().toString(),
        revokedAt == null ? null : java.sql.Timestamp.from(revokedAt));
  }
}
