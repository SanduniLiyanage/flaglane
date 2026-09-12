package io.github.sanduniliyanage.flaglane;

import java.nio.file.Files;
import java.nio.file.Path;
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
    registry.add("spring.datasource.url", this::getJdbcUrl);
    registry.add("spring.datasource.username", () -> APP_USER);
    registry.add("spring.datasource.password", () -> APP_PASSWORD);
    registry.add("spring.flyway.user", () -> MIGRATOR_USER);
    registry.add("spring.flyway.password", () -> MIGRATOR_PASSWORD);
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
