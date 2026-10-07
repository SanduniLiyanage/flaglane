package io.github.sanduniliyanage.flaglane.serving.domain;

import io.github.sanduniliyanage.flaglane.apikey.domain.KeyType;
import io.github.sanduniliyanage.flaglane.evaluation.Ruleset;
import java.util.Objects;
import java.util.UUID;

/**
 * One environment's ruleset at one {@code ruleset_version}, ready to serve: for each key type, the
 * engine's ruleset and the JSON body, plain and gzipped, built once when the environment changed
 * and never per request (NFR-PER-004, ADR-008, ADR-033). Immutable, so it is swapped in whole and
 * read without locks.
 *
 * <p>A client key gets client-side-visible flags only (FR-KEY-005) and no overrides at all
 * (FR-KEY-008), on {@code GET /sdk/config} and {@code POST /sdk/evaluate} alike, because both read
 * the same {@link #ruleset(KeyType)}.
 */
public record RulesetSnapshot(
    UUID environmentId,
    String environmentKey,
    long version,
    Ruleset serverRuleset,
    ServedBody serverBody,
    Ruleset clientRuleset,
    ServedBody clientBody) {

  public RulesetSnapshot {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(serverRuleset, "serverRuleset");
    Objects.requireNonNull(clientRuleset, "clientRuleset");
    Objects.requireNonNull(serverBody, "serverBody");
    Objects.requireNonNull(clientBody, "clientBody");
  }

  public Ruleset ruleset(KeyType type) {
    return type == KeyType.CLIENT ? clientRuleset : serverRuleset;
  }

  /** The body {@code GET /sdk/config} sends this key type. */
  public ServedBody served(KeyType type) {
    return type == KeyType.CLIENT ? clientBody : serverBody;
  }

  /** The same body as JSON text. */
  public String body(KeyType type) {
    return served(type).json();
  }

  /**
   * {@code "<version>-<key type>"}. Never the version alone: one URL serves a server key and a
   * client key different bodies at the same version, and an ETag shared between them would let a
   * client key's cached copy stand for a server key's ruleset, or the reverse (FR-SRV-001).
   */
  public String etag(KeyType type) {
    return "\"" + version + "-" + type.value() + "\"";
  }
}
