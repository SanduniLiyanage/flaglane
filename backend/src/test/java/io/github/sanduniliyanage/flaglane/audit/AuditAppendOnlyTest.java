package io.github.sanduniliyanage.flaglane.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sanduniliyanage.flaglane.FlaglanePostgres;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Suite 10 of {@code docs/TESTING.md}: {@code audit_entries} is append-only at the database
 * (FR-AUD-002). Every attempt to change or remove an entry fails inside PostgreSQL, as the
 * application role and as the owning role, and the entry is still there afterwards.
 *
 * <p>The owning role is the case that matters. The application role is stopped by its grants, and a
 * grant is undone by one statement from whoever owns the table; the trigger is what stops the owner
 * too.
 */
@Testcontainers
class AuditAppendOnlyTest {

  @Container private static final FlaglanePostgres POSTGRES = new FlaglanePostgres();

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID PROJECT_ID = UUID.randomUUID();
  private static final UUID ENTRY_ID = UUID.randomUUID();

  private static final String INSERT_ENTRY =
      "insert into audit_entries (id, project_id, actor_id, action, created_at)"
          + " values (?, ?, ?, ?, now())";
  private static final String UPDATE_ENTRY =
      "update audit_entries set action = 'flag.updated' where id = ?";
  private static final String DELETE_ENTRY = "delete from audit_entries where id = ?";
  private static final String TRUNCATE_ENTRIES = "truncate audit_entries";

  @BeforeAll
  static void migrateAndSeedOneEntry() throws Exception {
    POSTGRES.migrate();

    try (Connection migrator = POSTGRES.connectAsMigrator()) {
      try (PreparedStatement user =
          migrator.prepareStatement(
              "insert into users (id, email, password_hash, created_at) values (?, ?, ?, now())")) {
        user.setObject(1, USER_ID);
        user.setString(2, "audit@example.com");
        user.setString(3, "hash");
        user.executeUpdate();
      }
      try (PreparedStatement project =
          migrator.prepareStatement(
              "insert into projects (id, owner_id, key, name, created_at)"
                  + " values (?, ?, ?, ?, now())")) {
        project.setObject(1, PROJECT_ID);
        project.setObject(2, USER_ID);
        project.setString(3, "audit");
        project.setString(4, "Audit");
        project.executeUpdate();
      }
      insertEntry(migrator, ENTRY_ID, "project.created");
    }
  }

  @Test
  void applicationRoleAppendsEntries() throws Exception {
    try (Connection app = POSTGRES.connectAsApp()) {
      assertThat(insertEntry(app, UUID.randomUUID(), "flag.created")).isEqualTo(1);
    }
  }

  @Test
  void owningRoleCannotUpdateAnEntry() throws Exception {
    try (Connection migrator = POSTGRES.connectAsMigrator()) {
      assertThatThrownBy(() -> executeOnSeededEntry(migrator, UPDATE_ENTRY))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("audit_entries is append-only: UPDATE is not permitted");

      assertThat(seededEntryAction(migrator)).isEqualTo("project.created");
    }
  }

  @Test
  void owningRoleCannotDeleteAnEntry() throws Exception {
    try (Connection migrator = POSTGRES.connectAsMigrator()) {
      assertThatThrownBy(() -> executeOnSeededEntry(migrator, DELETE_ENTRY))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("audit_entries is append-only: DELETE is not permitted");

      assertThat(seededEntryAction(migrator)).isEqualTo("project.created");
    }
  }

  @Test
  void owningRoleCannotTruncateTheTable() throws Exception {
    try (Connection migrator = POSTGRES.connectAsMigrator();
        Statement sql = migrator.createStatement()) {
      assertThatThrownBy(() -> sql.execute(TRUNCATE_ENTRIES))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("audit_entries is append-only: TRUNCATE is not permitted");

      assertThat(seededEntryAction(migrator)).isEqualTo("project.created");
    }
  }

  @Test
  void applicationRoleCannotUpdateDeleteOrTruncate() throws Exception {
    try (Connection app = POSTGRES.connectAsApp();
        Statement sql = app.createStatement()) {
      assertThatThrownBy(() -> executeOnSeededEntry(app, UPDATE_ENTRY))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied for table audit_entries");

      assertThatThrownBy(() -> executeOnSeededEntry(app, DELETE_ENTRY))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied for table audit_entries");

      assertThatThrownBy(() -> sql.execute(TRUNCATE_ENTRIES))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied for table audit_entries");

      assertThat(seededEntryAction(app)).isEqualTo("project.created");
    }
  }

  private static int insertEntry(Connection connection, UUID id, String action)
      throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(INSERT_ENTRY)) {
      insert.setObject(1, id);
      insert.setObject(2, PROJECT_ID);
      insert.setObject(3, USER_ID);
      insert.setString(4, action);
      return insert.executeUpdate();
    }
  }

  private static void executeOnSeededEntry(Connection connection, String statement)
      throws SQLException {
    try (PreparedStatement sql = connection.prepareStatement(statement)) {
      sql.setObject(1, ENTRY_ID);
      sql.executeUpdate();
    }
  }

  private static String seededEntryAction(Connection connection) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement("select action from audit_entries where id = ?")) {
      select.setObject(1, ENTRY_ID);
      try (ResultSet rows = select.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }
}
