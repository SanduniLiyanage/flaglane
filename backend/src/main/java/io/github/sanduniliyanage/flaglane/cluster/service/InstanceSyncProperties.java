package io.github.sanduniliyanage.flaglane.cluster.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How instances hear each other's changes (ADR-041).
 *
 * @param enabled whether to announce changes and listen for others', from {@code
 *     FLAGLANE_INSTANCE_SYNC}; true by default, and harmless on a single instance
 * @param listenUrl where the listening connection goes, from {@code FLAGLANE_DB_LISTEN_URL};
 *     defaults to the application's own database URL. It must reach PostgreSQL directly or through
 *     a pooler in session mode: a pooler in transaction mode cannot carry {@code LISTEN}
 * @param username the application role, as the application connects
 * @param password that role's password
 */
@ConfigurationProperties("flaglane.instance-sync")
public record InstanceSyncProperties(
    boolean enabled, String listenUrl, String username, String password) {

  /** Never prints the password, whoever logs this object. */
  @Override
  public String toString() {
    return "InstanceSyncProperties[enabled="
        + enabled
        + ", listenUrl="
        + listenUrl
        + ", username="
        + username
        + ", password=<redacted>]";
  }
}
