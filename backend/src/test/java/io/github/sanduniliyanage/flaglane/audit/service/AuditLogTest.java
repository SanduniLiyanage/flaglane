package io.github.sanduniliyanage.flaglane.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditAction;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditEvent;
import io.github.sanduniliyanage.flaglane.common.clock.ClockConfiguration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The audit writer against the real table, its action check and its append-only trigger. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(initializers = FlaglaneIntegrationTest.SharedDatabase.class)
@Import({AuditLog.class, ClockConfiguration.class})
class AuditLogTest {

  private final AuditLog audit;
  private final JdbcTemplate database;
  private final TestEntityManager entityManager;

  private UUID userId;
  private UUID projectId;

  AuditLogTest(
      @Autowired AuditLog audit,
      @Autowired JdbcTemplate database,
      @Autowired TestEntityManager entityManager) {
    this.audit = audit;
    this.database = database;
    this.entityManager = entityManager;
  }

  @BeforeEach
  void aUserAndAProject() {
    userId = UUID.randomUUID();
    projectId = UUID.randomUUID();
    database.update(
        "insert into users (id, email, password_hash, created_at) values (?, ?, 'h', now())",
        userId,
        userId + "@example.com");
    database.update(
        "insert into projects (id, owner_id, key, name, created_at) values (?, ?, ?, 'P', now())",
        projectId,
        userId,
        "audit-" + projectId.toString().substring(0, 8));
  }

  @ParameterizedTest
  @EnumSource(AuditAction.class)
  void everyActionInTheVocabularyIsOneTheDatabaseAccepts(AuditAction action) {
    audit.record(AuditEvent.of(action, projectId, userId));
    entityManager.flush();

    assertThat(
            database.queryForObject(
                "select action from audit_entries where project_id = ?", String.class, projectId))
        .isEqualTo(action.value());
  }

  @Test
  void anEntryRecordsTheEventAndItsPayloadsAsJson() {
    UUID environmentId = UUID.randomUUID();

    audit.record(
        AuditEvent.of(AuditAction.ENVIRONMENT_DELETED, projectId, userId)
            .environment(environmentId)
            .previous(Map.of("key", "qa", "name", "QA")));
    entityManager.flush();

    Map<String, Object> row =
        database.queryForMap(
            "select environment_id, flag_id, actor_id, previous_value->>'key' as previous_key,"
                + " new_value from audit_entries where project_id = ?",
            projectId);
    assertThat(row)
        .containsEntry("environment_id", environmentId)
        .containsEntry("flag_id", null)
        .containsEntry("actor_id", userId)
        .containsEntry("previous_key", "qa")
        .containsEntry("new_value", null);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void anEntryCannotBeRecordedOutsideTheTransactionOfTheChangeItDescribes() {
    assertThatExceptionOfType(IllegalTransactionStateException.class)
        .isThrownBy(
            () -> audit.record(AuditEvent.of(AuditAction.PROJECT_CREATED, projectId, userId)));
  }
}
