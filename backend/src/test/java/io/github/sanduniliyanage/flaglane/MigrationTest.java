package io.github.sanduniliyanage.flaglane;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MigrationTest {

  private static final List<String> EXPECTED_TABLES =
      List.of(
          "flyway_schema_history",
          "users",
          "projects",
          "environments",
          "flags",
          "api_keys",
          "flag_configs",
          "targeting_rules",
          "user_overrides",
          "audit_entries");

  @Container private static final FlaglanePostgres POSTGRES = new FlaglanePostgres();

  @Test
  void migrateAppliesBaselineSchemaAsMigratorAndIsIdempotent() throws Exception {
    Flyway flyway =
        Flyway.configure()
            .dataSource(
                POSTGRES.getJdbcUrl(),
                FlaglanePostgres.MIGRATOR_USER,
                FlaglanePostgres.MIGRATOR_PASSWORD)
            .load();

    MigrateResult firstRun = flyway.migrate();
    assertThat(firstRun.migrationsExecuted).isGreaterThan(0);

    MigrateResult secondRun = flyway.migrate();
    assertThat(secondRun.migrationsExecuted).isZero();

    try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
        ResultSet resultSet =
            connection.getMetaData().getTables(null, "public", "%", new String[] {"TABLE"})) {
      List<String> actualTables = new ArrayList<>();
      while (resultSet.next()) {
        actualTables.add(resultSet.getString("TABLE_NAME"));
      }
      assertThat(actualTables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    }
  }
}
