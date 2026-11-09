package io.github.sanduniliyanage.flaglane.cluster.service;

import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKeyEvents;
import io.github.sanduniliyanage.flaglane.cluster.domain.Change;
import io.github.sanduniliyanage.flaglane.cluster.domain.InstanceId;
import io.github.sanduniliyanage.flaglane.project.domain.RulesetChanged;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells every other instance what a write changed, in the write's own transaction (ADR-041).
 *
 * <p>{@code pg_notify} runs just before the transaction commits, inside it, and PostgreSQL delivers
 * a notification only when its transaction commits: a write that rolls back announces nothing, and
 * one that commits is announced to every listening instance, this one included, which recognises
 * its own and passes over it. What is announced is which environment or key, never what changed:
 * each instance reads that from the database.
 */
@Component
@ConditionalOnProperty(prefix = "flaglane.instance-sync", name = "enabled", havingValue = "true")
public class ChangeNotifier {

  /** The channel every instance listens on. */
  public static final String CHANNEL = "flaglane";

  private final JdbcTemplate jdbc;
  private final InstanceId instance;

  public ChangeNotifier(JdbcTemplate jdbc, InstanceId instance) {
    this.jdbc = jdbc;
    this.instance = instance;
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void rulesetChanged(RulesetChanged event) {
    event.environmentIds().forEach(id -> announce(Change.Kind.RULESET, id));
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void keyIssued(ApiKeyEvents.Issued event) {
    announce(Change.Kind.KEY, event.credential().keyId());
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void keyRevoked(ApiKeyEvents.Revoked event) {
    announce(Change.Kind.KEY, event.credential().keyId());
  }

  private void announce(Change.Kind kind, UUID id) {
    jdbc.queryForList(
        "select pg_notify(?, ?)", CHANNEL, new Change(instance.value(), kind, id).encode());
  }
}
