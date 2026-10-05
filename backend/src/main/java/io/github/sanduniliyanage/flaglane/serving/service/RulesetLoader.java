package io.github.sanduniliyanage.flaglane.serving.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sanduniliyanage.flaglane.evaluation.FlagConfig;
import io.github.sanduniliyanage.flaglane.evaluation.Ruleset;
import io.github.sanduniliyanage.flaglane.evaluation.TargetingRule;
import io.github.sanduniliyanage.flaglane.serving.domain.RulesetSnapshot;
import io.github.sanduniliyanage.flaglane.serving.domain.ServedRuleset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads one environment's ruleset from the database and builds the snapshot the serving path
 * answers from. Four queries, whatever the number of flags: configurations with their flags, rules,
 * overrides, and the environment row. They run in one {@code REPEATABLE READ} transaction, so the
 * version the snapshot carries is the version of exactly the rows it was built from, and an ETag
 * can never name a ruleset it does not describe.
 *
 * <p>Archived flags are not served.
 */
@Component
public class RulesetLoader {

  private static final TypeReference<List<Object>> VALUES = new TypeReference<>() {};

  private final JdbcTemplate database;
  private final TransactionTemplate snapshotReads;
  private final ObjectMapper json;

  public RulesetLoader(
      JdbcTemplate database, PlatformTransactionManager transactions, ObjectMapper json) {
    this.database = database;
    this.json = json;
    this.snapshotReads = new TransactionTemplate(transactions);
    snapshotReads.setReadOnly(true);
    snapshotReads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  /** Every environment's current version: what the cache should hold. */
  public Map<UUID, Long> versions() {
    Map<UUID, Long> versions = new HashMap<>();
    database.query(
        "select id, ruleset_version from environments",
        row -> {
          versions.put(row.getObject("id", UUID.class), row.getLong("ruleset_version"));
        });
    return versions;
  }

  /** The environment's snapshot, or empty if the environment no longer exists. */
  public Optional<RulesetSnapshot> load(UUID environmentId) {
    return snapshotReads.execute(status -> read(environmentId));
  }

  private record Config(
      UUID id,
      String flagKey,
      boolean clientSideVisible,
      boolean enabled,
      boolean offValue,
      boolean fallthroughValue,
      int rolloutBasisPoints,
      String rolloutSalt) {}

  private Optional<RulesetSnapshot> read(UUID environmentId) {
    List<Map<String, Object>> environment =
        database.queryForList(
            "select key, ruleset_version from environments where id = ?", environmentId);
    if (environment.isEmpty()) {
      return Optional.empty();
    }
    String environmentKey = (String) environment.getFirst().get("key");
    long version = ((Number) environment.getFirst().get("ruleset_version")).longValue();

    List<Config> configs =
        database.query(
            "select c.id, f.key, f.client_side_visible, c.enabled, c.off_value,"
                + " c.fallthrough_value, c.rollout_basis_points, c.rollout_salt"
                + " from flag_configs c join flags f on f.id = c.flag_id"
                + " where c.environment_id = ? and f.archived_at is null order by f.key",
            (row, index) ->
                new Config(
                    row.getObject("id", UUID.class),
                    row.getString("key"),
                    row.getBoolean("client_side_visible"),
                    row.getBoolean("enabled"),
                    row.getBoolean("off_value"),
                    row.getBoolean("fallthrough_value"),
                    row.getInt("rollout_basis_points"),
                    row.getString("rollout_salt")),
            environmentId);

    Map<UUID, List<ServedRuleset.Rule>> rules = new HashMap<>();
    database.query(
        "select r.flag_config_id, r.priority, r.attribute, r.operator, r.match_values::text"
            + " as match_values, r.result_value from targeting_rules r"
            + " join flag_configs c on c.id = r.flag_config_id"
            + " where c.environment_id = ? order by r.flag_config_id, r.priority",
        row -> {
          rules
              .computeIfAbsent(row.getObject("flag_config_id", UUID.class), id -> new ArrayList<>())
              .add(
                  new ServedRuleset.Rule(
                      row.getInt("priority"),
                      row.getString("attribute"),
                      row.getString("operator"),
                      values(row.getString("match_values")),
                      row.getBoolean("result_value")));
        },
        environmentId);

    Map<UUID, List<ServedRuleset.UserOverride>> overrides = new HashMap<>();
    database.query(
        "select o.flag_config_id, o.user_key, o.value from user_overrides o"
            + " join flag_configs c on c.id = o.flag_config_id"
            + " where c.environment_id = ? order by o.flag_config_id, o.user_key",
        row -> {
          overrides
              .computeIfAbsent(row.getObject("flag_config_id", UUID.class), id -> new ArrayList<>())
              .add(
                  new ServedRuleset.UserOverride(
                      row.getString("user_key"), row.getBoolean("value")));
        },
        environmentId);

    List<FlagConfig> serverFlags = new ArrayList<>();
    List<FlagConfig> clientFlags = new ArrayList<>();
    List<ServedRuleset.Flag> serverServed = new ArrayList<>();
    List<ServedRuleset.Flag> clientServed = new ArrayList<>();
    for (Config config : configs) {
      List<ServedRuleset.Rule> flagRules = rules.getOrDefault(config.id(), List.of());
      List<ServedRuleset.UserOverride> flagOverrides =
          overrides.getOrDefault(config.id(), List.of());
      serverFlags.add(flagConfig(config, flagRules, flagOverrides));
      serverServed.add(served(config, flagRules, flagOverrides));
      if (config.clientSideVisible()) {
        clientFlags.add(flagConfig(config, flagRules, List.of()));
        clientServed.add(served(config, flagRules, null));
      }
    }

    return Optional.of(
        new RulesetSnapshot(
            environmentId,
            environmentKey,
            version,
            Ruleset.of(environmentKey, version, serverFlags),
            write(new ServedRuleset(environmentKey, version, serverServed)),
            Ruleset.of(environmentKey, version, clientFlags),
            write(new ServedRuleset(environmentKey, version, clientServed))));
  }

  private static FlagConfig flagConfig(
      Config config, List<ServedRuleset.Rule> rules, List<ServedRuleset.UserOverride> overrides) {
    FlagConfig.Builder builder =
        FlagConfig.builder(config.flagKey())
            .enabled(config.enabled())
            .fallthroughValue(config.fallthroughValue())
            .rolloutBasisPoints(config.rolloutBasisPoints())
            .rolloutSalt(config.rolloutSalt());
    rules.forEach(
        rule ->
            builder.rule(
                new TargetingRule(
                    rule.priority(),
                    rule.attribute(),
                    rule.operator(),
                    rule.matchValues(),
                    rule.resultValue())));
    overrides.forEach(override -> builder.override(override.userKey(), override.value()));
    return builder.build();
  }

  private static ServedRuleset.Flag served(
      Config config, List<ServedRuleset.Rule> rules, List<ServedRuleset.UserOverride> overrides) {
    return new ServedRuleset.Flag(
        config.flagKey(),
        config.enabled(),
        config.offValue(),
        config.fallthroughValue(),
        config.rolloutBasisPoints(),
        config.rolloutSalt(),
        overrides,
        rules);
  }

  private List<Object> values(String text) {
    try {
      return json.readValue(text, VALUES);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("targeting_rules.match_values is not a JSON array", e);
    }
  }

  private String write(ServedRuleset ruleset) {
    try {
      return json.writeValueAsString(ruleset);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("A ruleset could not be serialised", e);
    }
  }
}
