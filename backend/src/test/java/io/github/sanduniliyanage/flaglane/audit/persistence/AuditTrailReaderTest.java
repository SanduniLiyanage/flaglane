package io.github.sanduniliyanage.flaglane.audit.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditCursor;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantScopes;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * Keyset pagination and naming against the real schema (FR-AUD-003). Entries are inserted with
 * chosen timestamps and ids, so ties on {@code created_at} and their order by id are deliberate:
 * three entries share one instant, and the ids are chosen so their order is plain to read.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(initializers = FlaglaneIntegrationTest.SharedDatabase.class)
@Import({AuditTrailReader.class, TenantResolver.class})
class AuditTrailReaderTest {

  private static final Instant T0 = Instant.parse("2026-10-06T09:00:00.000001Z");

  private final AuditTrailReader reader;
  private final TenantResolver tenants;
  private final JdbcTemplate database;

  private UUID user;
  private UUID projectId;
  private UUID flag;
  private UUID production;
  private UUID staging;
  private ProjectScope project;
  private EnvironmentScope productionScope;
  private final List<UUID> newestFirst = new ArrayList<>();

  AuditTrailReaderTest(
      @Autowired AuditTrailReader reader,
      @Autowired TenantResolver tenants,
      @Autowired JdbcTemplate database) {
    this.reader = reader;
    this.tenants = tenants;
    this.database = database;
  }

  @BeforeEach
  void aProjectWithAHistory() {
    user = UUID.randomUUID();
    projectId = UUID.randomUUID();
    flag = UUID.randomUUID();
    production = UUID.randomUUID();
    staging = UUID.randomUUID();
    String key = "audit-" + projectId.toString().substring(0, 8);
    database.update(
        "insert into users (id, email, password_hash, display_name, created_at)"
            + " values (?, ?, 'h', 'Amara', now())",
        user,
        user + "@example.com");
    database.update(
        "insert into projects (id, owner_id, key, name, created_at) values (?, ?, ?, 'P', now())",
        projectId,
        user,
        key);
    database.update(
        "insert into flags (id, project_id, key, name, client_side_visible, created_at)"
            + " values (?, ?, 'checkout', 'Checkout', false, now())",
        flag,
        projectId);
    environment(production, "production");
    environment(staging, "staging");

    UUID project1 = entry(1, "project.created", null, null, 0, null, "{\"key\":\"" + key + "\"}");
    UUID flag2 = entry(2, "flag.created", null, flag, 1, null, "{\"key\":\"checkout\"}");
    UUID config3 = entry(3, "config.updated", production, flag, 2, "{\"a\":1}", "{\"a\":2}");
    UUID config4 = entry(4, "config.updated", production, flag, 2, "{\"a\":2}", "{\"a\":3}");
    UUID config5 = entry(5, "config.updated", production, flag, 2, "{\"a\":3}", "{\"a\":4}");
    UUID created6 =
        entry(6, "environment.created", staging, null, 3, null, "{\"key\":\"staging\"}");
    UUID deleted7 =
        entry(7, "environment.deleted", staging, null, 4, "{\"key\":\"staging\"}", null);
    database.update("delete from environments where id = ?", staging);
    newestFirst.addAll(List.of(deleted7, created6, config5, config4, config3, flag2, project1));

    anotherTenantsEntryNewerThanAll();
    project = tenants.project(TenantScopes.owner(user), key);
    productionScope = tenants.environment(project, "production");
  }

  @Test
  void entriesComeNewestFirstWithTiesInDescendingIdOrder() {
    List<AuditTrailRow> rows = reader.page(project, null, null, null, 100);

    assertThat(rows).extracting(AuditTrailRow::id).containsExactlyElementsOf(newestFirst);
  }

  @Test
  void pagingTwoAtATimeVisitsEveryEntryOnceInOrder() {
    List<UUID> visited = new ArrayList<>();
    AuditCursor cursor = null;
    for (int page = 0; page < 10; page++) {
      List<AuditTrailRow> rows = reader.page(project, null, null, cursor, 2);
      rows.forEach(row -> visited.add(row.id()));
      if (rows.size() < 2) {
        break;
      }
      AuditTrailRow last = rows.getLast();
      cursor = new AuditCursor(last.createdAt(), last.id());
    }

    assertThat(visited).containsExactlyElementsOf(newestFirst);
  }

  @Test
  void anEntryRecordedWhileReadingDoesNotShiftTheNextPage() {
    List<AuditTrailRow> first = reader.page(project, null, null, null, 3);
    entry(8, "flag.updated", null, flag, 9, "{}", "{}");

    AuditTrailRow last = first.getLast();
    List<AuditTrailRow> second =
        reader.page(project, null, null, new AuditCursor(last.createdAt(), last.id()), 3);

    assertThat(second)
        .extracting(AuditTrailRow::id)
        .containsExactlyElementsOf(newestFirst.subList(3, 6));
  }

  @Test
  void aPageCanBeNarrowedToAnEnvironmentAFlagOrBoth() {
    assertThat(reader.page(project, productionScope, null, null, 100))
        .extracting(AuditTrailRow::id)
        .containsExactlyElementsOf(newestFirst.subList(2, 5));
    assertThat(reader.page(project, null, "checkout", null, 100))
        .extracting(AuditTrailRow::id)
        .containsExactlyElementsOf(newestFirst.subList(2, 6));
    assertThat(reader.page(project, productionScope, "checkout", null, 100)).hasSize(3);
    assertThat(reader.page(project, null, "no-such-flag", null, 100)).isEmpty();
  }

  @Test
  void eachEntryNamesItsActorEnvironmentAndFlag() {
    AuditTrailRow config = reader.page(project, productionScope, null, null, 1).getFirst();

    assertThat(config.actorEmail()).isEqualTo(user + "@example.com");
    assertThat(config.actorDisplayName()).isEqualTo("Amara");
    assertThat(config.environmentKey()).isEqualTo("production");
    assertThat(config.environmentDeleted()).isFalse();
    assertThat(config.flagKey()).isEqualTo("checkout");
    assertThat(config.previousValueJson()).isEqualTo("{\"a\": 3}");
    assertThat(config.newValueJson()).isEqualTo("{\"a\": 4}");
  }

  @Test
  void aDeletedEnvironmentIsNamedByTheKeyItHadAndMarkedDeleted() {
    List<AuditTrailRow> rows = reader.page(project, null, null, null, 2);

    assertThat(rows)
        .allSatisfy(
            row -> {
              assertThat(row.environmentKey()).isEqualTo("staging");
              assertThat(row.environmentDeleted()).isTrue();
              assertThat(row.flagKey()).isNull();
            });
  }

  @Test
  void anEntryWithNoEnvironmentOrFlagNamesNeither() {
    AuditTrailRow created = reader.page(project, null, null, null, 100).getLast();

    assertThat(created.action()).isEqualTo("project.created");
    assertThat(created.environmentKey()).isNull();
    assertThat(created.environmentDeleted()).isFalse();
    assertThat(created.flagKey()).isNull();
    assertThat(created.previousValueJson()).isNull();
  }

  private void environment(UUID id, String key) {
    database.update(
        "insert into environments (id, project_id, key, name, created_at)"
            + " values (?, ?, ?, ?, now())",
        id,
        projectId,
        key,
        key);
  }

  /** An entry {@code step} microseconds after T0, with an id that sorts by {@code n}. */
  private UUID entry(
      int n, String action, UUID environment, UUID flagId, int step, String previous, String next) {
    UUID id =
        UUID.fromString(
            String.format("%08d-0000-4000-8000-%s", n, projectId.toString().substring(24)));
    database.update(
        "insert into audit_entries (id, project_id, environment_id, flag_id, actor_id, action,"
            + " previous_value, new_value, created_at)"
            + " values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)",
        id,
        projectId,
        environment,
        flagId,
        user,
        action,
        previous,
        next,
        OffsetDateTime.ofInstant(T0.plusNanos(step * 1_000L), ZoneOffset.UTC));
    return id;
  }

  private void anotherTenantsEntryNewerThanAll() {
    UUID otherUser = UUID.randomUUID();
    UUID otherProject = UUID.randomUUID();
    database.update(
        "insert into users (id, email, password_hash, created_at) values (?, ?, 'h', now())",
        otherUser,
        otherUser + "@example.com");
    database.update(
        "insert into projects (id, owner_id, key, name, created_at) values (?, ?, ?, 'Q', now())",
        otherProject,
        otherUser,
        "other-" + otherProject.toString().substring(0, 8));
    database.update(
        "insert into audit_entries (id, project_id, actor_id, action, created_at)"
            + " values (?, ?, ?, 'project.created', ?)",
        UUID.randomUUID(),
        otherProject,
        otherUser,
        OffsetDateTime.ofInstant(T0.plusSeconds(60), ZoneOffset.UTC));
  }
}
