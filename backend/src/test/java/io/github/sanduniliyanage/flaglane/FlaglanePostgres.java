package io.github.sanduniliyanage.flaglane;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL 16 provisioned exactly as the Compose stack provisions it: the container runs {@code
 * docker/postgres/init-roles.sh}, so {@code flaglane_migrator} owns the schema and {@code
 * flaglane_app} runs the application. Tests that use the bootstrap superuser would pass while
 * proving nothing about the privilege split, which is why this fixture never exposes it.
 */
public final class FlaglanePostgres extends PostgreSQLContainer<FlaglanePostgres> {

  public static final String MIGRATOR_USER = "flaglane_migrator";
  public static final String MIGRATOR_PASSWORD = "migrator-test-password";
  public static final String APP_USER = "flaglane_app";
  public static final String APP_PASSWORD = "app-test-password";

  private static final String INIT_SCRIPT_PROPERTY = "flaglane.postgres.initScript";

  public FlaglanePostgres() {
    super("postgres:16");
    withCopyFileToContainer(
        MountableFile.forHostPath(initScript(), 0755), "/docker-entrypoint-initdb.d/10-roles.sh");
    withEnv("FLAGLANE_DB_MIGRATOR_PASSWORD", MIGRATOR_PASSWORD);
    withEnv("FLAGLANE_DB_APP_PASSWORD", APP_PASSWORD);
  }

  /** Points a Spring context at this container with the two-role configuration. */
  public void registerProperties(DynamicPropertyRegistry registry) {
    connectionProperties().forEach((name, value) -> registry.add(name, () -> value));
  }

  /** The same two-role configuration, for contexts built by hand rather than by annotation. */
  public Map<String, String> connectionProperties() {
    return Map.of(
        "spring.datasource.url", getJdbcUrl(),
        "spring.datasource.username", APP_USER,
        "spring.datasource.password", APP_PASSWORD,
        "spring.flyway.user", MIGRATOR_USER,
        "spring.flyway.password", MIGRATOR_PASSWORD);
  }

  /**
   * Runs every migration as {@code flaglane_migrator}, exactly as the application does at startup.
   */
  public MigrateResult migrate() {
    return Flyway.configure()
        .dataSource(getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD)
        .load()
        .migrate();
  }

  /** A plain JDBC connection as the application role, for asserting what its grants allow. */
  public Connection connectAsApp() throws SQLException {
    return DriverManager.getConnection(getJdbcUrl(), APP_USER, APP_PASSWORD);
  }

  /** A plain JDBC connection as the schema owner, for asserting what holds even for the owner. */
  public Connection connectAsMigrator() throws SQLException {
    return DriverManager.getConnection(getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
  }

  private static Path initScript() {
    String location = System.getProperty(INIT_SCRIPT_PROPERTY);
    if (location == null) {
      throw new IllegalStateException(
          "System property " + INIT_SCRIPT_PROPERTY + " is not set; run tests through Gradle");
    }
    Path script = Path.of(location);
    if (!Files.isRegularFile(script)) {
      throw new IllegalStateException("Role provisioning script not found at " + script);
    }
    return script;
  }
}
