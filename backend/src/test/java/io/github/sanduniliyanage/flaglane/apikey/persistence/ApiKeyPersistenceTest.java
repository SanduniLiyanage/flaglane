package io.github.sanduniliyanage.flaglane.apikey.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import io.github.sanduniliyanage.flaglane.apikey.domain.KeyType;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantScopes;
import java.time.Instant;
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

/** API keys as the application role against the migrated schema. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(initializers = FlaglaneIntegrationTest.SharedDatabase.class)
@Import(TenantResolver.class)
class ApiKeyPersistenceTest {

  private static final Instant NOW = Instant.parse("2026-10-05T09:30:00Z");

  private final ApiKeyRepository keys;
  private final TenantResolver tenants;
  private final JdbcTemplate database;
  private final TestEntityManager entityManager;

  private EnvironmentScope production;
  private EnvironmentScope staging;

  ApiKeyPersistenceTest(
      @Autowired ApiKeyRepository keys,
      @Autowired TenantResolver tenants,
      @Autowired JdbcTemplate database,
      @Autowired TestEntityManager entityManager) {
    this.keys = keys;
    this.tenants = tenants;
    this.database = database;
    this.entityManager = entityManager;
  }

  @BeforeEach
  void aProjectWithTwoEnvironments() {
    UUID user = UUID.randomUUID();
    UUID project = UUID.randomUUID();
    String projectKey = "keys-" + project.toString().substring(0, 8);
    database.update(
        "insert into users (id, email, password_hash, created_at) values (?, ?, 'h', now())",
        user,
        user + "@example.com");
    database.update(
        "insert into projects (id, owner_id, key, name, created_at) values (?, ?, ?, 'P', now())",
        project,
        user,
        projectKey);
    for (String environment : new String[] {"production", "staging"}) {
      database.update(
          "insert into environments (id, project_id, key, name, created_at)"
              + " values (?, ?, ?, ?, now())",
          UUID.randomUUID(),
          project,
          environment,
          environment);
    }
    var scope = tenants.project(TenantScopes.owner(user), projectKey);
    production = tenants.environment(scope, "production");
    staging = tenants.environment(scope, "staging");
  }

  @Test
  void keysAreListedForTheirOwnEnvironmentOnly() {
    ApiKeyEntity mine = key(production, "mine");
    key(staging, "other");

    assertThat(keys.findAll(production)).extracting(ApiKeyEntity::id).containsExactly(mine.id());
  }

  @Test
  void aKeyIsOnlyFoundThroughItsOwnEnvironment() {
    ApiKeyEntity key = key(production, "k");

    assertThat(keys.find(production, key.id())).isPresent();
    assertThat(keys.find(staging, key.id())).isEmpty();
  }

  @Test
  void theKeyTypeIsStoredAsTheDatabaseSpellsIt() {
    ApiKeyEntity key = key(production, "client");

    assertThat(
            database.queryForObject(
                "select key_type from api_keys where id = ?", String.class, key.id()))
        .isEqualTo("client");
  }

  @Test
  void twoKeysCannotShareAHash() {
    ApiKeyEntity first = key(production, "first");

    assertThatExceptionOfType(DataIntegrityViolationException.class)
        .isThrownBy(
            () ->
                keys.saveAndFlush(
                    new ApiKeyEntity(
                        UUID.randomUUID(),
                        production.environmentId(),
                        first.getKeyHash(),
                        "flg_srv_abcdefgh",
                        KeyType.SERVER,
                        "second",
                        NOW)));
  }

  @Test
  void revokedKeysAreNotLive() {
    ApiKeyEntity live = key(production, "live");
    ApiKeyEntity revoked = key(production, "revoked");
    revoked.revoke(NOW);
    entityManager.flush();

    assertThat(keys.findAllLive())
        .extracting(ApiKeyEntity::id)
        .contains(live.id())
        .doesNotContain(revoked.id());
  }

  @Test
  void lastUsedOnlyEverMovesForward() {
    ApiKeyEntity key = key(production, "used");

    assertThat(keys.markUsed(key.id(), NOW.plusSeconds(120))).isEqualTo(1);
    assertThat(keys.markUsed(key.id(), NOW.plusSeconds(60))).isZero();
    entityManager.clear();

    assertThat(keys.find(production, key.id()).orElseThrow().getLastUsedAt())
        .isEqualTo(NOW.plusSeconds(120));
  }

  private ApiKeyEntity key(EnvironmentScope environment, String name) {
    KeyType type = name.equals("client") ? KeyType.CLIENT : KeyType.SERVER;
    return keys.saveAndFlush(
        new ApiKeyEntity(
            UUID.randomUUID(),
            environment.environmentId(),
            UUID.randomUUID().toString().replace("-", "") + "00000000000000000000000000000000",
            type.marker() + "abcdefgh",
            type,
            name,
            NOW));
  }
}
