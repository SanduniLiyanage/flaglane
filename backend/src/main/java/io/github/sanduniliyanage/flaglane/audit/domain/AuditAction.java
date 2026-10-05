package io.github.sanduniliyanage.flaglane.audit.domain;

/**
 * The fixed vocabulary of {@code audit_entries.action} (docs/DATABASE.md), which a database check
 * also enforces. Adding an action is a migration, because the vocabulary is part of the contract
 * with anyone reading the log.
 */
public enum AuditAction {
  PROJECT_CREATED("project.created"),
  ENVIRONMENT_CREATED("environment.created"),
  ENVIRONMENT_DELETED("environment.deleted"),
  FLAG_CREATED("flag.created"),
  FLAG_UPDATED("flag.updated"),
  FLAG_ARCHIVED("flag.archived"),
  FLAG_RESTORED("flag.restored"),
  CONFIG_UPDATED("config.updated"),
  RULES_REPLACED("rules.replaced"),
  OVERRIDES_REPLACED("overrides.replaced"),
  KEY_CREATED("key.created"),
  KEY_REVOKED("key.revoked");

  private final String value;

  AuditAction(String value) {
    this.value = value;
  }

  /** As stored. */
  public String value() {
    return value;
  }
}
