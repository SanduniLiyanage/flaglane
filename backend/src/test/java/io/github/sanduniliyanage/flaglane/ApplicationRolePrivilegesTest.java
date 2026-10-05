package io.github.sanduniliyanage.flaglane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * What {@code V2__roles.sql} lets the application role do, asserted as that role against the
 * migrated schema: read and write the tables it serves, append to the audit log, and nothing else.
 * The migrator owns everything and is never the role under test here.
 */
@Testcontainers
class ApplicationRolePrivilegesTest {

  @Container private static final FlaglanePostgres POSTGRES = new FlaglanePostgres();

  @BeforeAll
  static void migrate() {
    POSTGRES.migrate();
  }

  @Test
  void applicationRoleReadsAndWritesTheTablesItServes() throws Exception {
    UUID id = UUID.randomUUID();

    try (Connection app = POSTGRES.connectAsApp()) {
      try (PreparedStatement insert =
          app.prepareStatement(
              "insert into users (id, email, password_hash, created_at) values (?, ?, ?, now())")) {
        insert.setObject(1, id);
        insert.setString(2, "grants@example.com");
        insert.setString(3, "hash");
        insert.executeUpdate();
      }
      try (PreparedStatement update =
          app.prepareStatement("update users set display_name = ? where id = ?")) {
        update.setString(1, "Grants");
        update.setObject(2, id);
        update.executeUpdate();
      }

      try (PreparedStatement select =
          app.prepareStatement("select display_name from users where id = ?")) {
        select.setObject(1, id);
        try (ResultSet rows = select.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("Grants");
        }
      }

      try (PreparedStatement delete = app.prepareStatement("delete from users where id = ?")) {
        delete.setObject(1, id);
        assertThat(delete.executeUpdate()).isEqualTo(1);
      }
    }
  }

  @Test
  void applicationRoleHoldsNoDdlPrivilege() throws Exception {
    try (Connection app = POSTGRES.connectAsApp();
        Statement sql = app.createStatement()) {
      assertThatThrownBy(() -> sql.execute("create table smuggled (id integer)"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied for schema public");

      assertThatThrownBy(() -> sql.execute("alter table users add column smuggled integer"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("must be owner of table users");
    }
  }

  @Test
  void applicationRoleCannotChangeOrRemoveAuditEntries() throws Exception {
    try (Connection app = POSTGRES.connectAsApp();
        Statement sql = app.createStatement()) {
      assertThatThrownBy(() -> sql.execute("update audit_entries set action = 'flag.updated'"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied for table audit_entries");

      assertThatThrownBy(() -> sql.execute("delete from audit_entries"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied for table audit_entries");

      assertThatThrownBy(() -> sql.execute("truncate audit_entries"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied for table audit_entries");
    }
  }
}
