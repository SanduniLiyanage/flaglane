package io.github.sanduniliyanage.flaglane.cluster.service;

import io.github.sanduniliyanage.flaglane.apikey.service.ApiKeyCache;
import io.github.sanduniliyanage.flaglane.cluster.domain.Change;
import io.github.sanduniliyanage.flaglane.cluster.domain.InstanceId;
import io.github.sanduniliyanage.flaglane.serving.service.RulesetCache;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Hears what other instances change and brings this one's caches up to it (ADR-041).
 *
 * <ul>
 *   <li>Holds one connection of its own, outside the pool, so listening never takes a connection a
 *       request needs, and runs {@code LISTEN} on it.
 *   <li>An environment announced is rebuilt in the ruleset cache, which tells this instance's
 *       streams; a key announced reloads the key cache, which ends the streams and drops the
 *       allowance of any key no longer live.
 *   <li>Each time it connects, at startup and after every drop, it catches up: a notification sent
 *       while it was not listening is lost, not queued, so it reconciles the ruleset cache and
 *       reloads the keys before listening on. The caches' own minute's reconciliation stays as the
 *       backstop.
 *   <li>A connection can die without saying so, so it runs a query every 30 seconds; a failure of
 *       any kind closes the connection and opens another, with backoff and jitter up to 30 seconds.
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "flaglane.instance-sync", name = "enabled", havingValue = "true")
public class ChangeListener implements SmartLifecycle {

  private static final Logger LOG = LoggerFactory.getLogger(ChangeListener.class);

  /** How long one wait for notifications lasts before the loop looks around again. */
  static final int WAIT_MILLIS = 1_000;

  /** How often an otherwise quiet connection is proved alive. */
  static final Duration PROBE_EVERY = Duration.ofSeconds(30);

  static final Duration FIRST_RETRY = Duration.ofSeconds(1);
  static final Duration LONGEST_RETRY = Duration.ofSeconds(30);

  private final InstanceSyncProperties properties;
  private final InstanceId instance;
  private final RulesetCache rulesets;
  private final ApiKeyCache keys;

  private final Object lock = new Object();
  private boolean running;
  private Thread thread;
  private Connection connection;
  private volatile boolean listening;

  public ChangeListener(
      InstanceSyncProperties properties,
      InstanceId instance,
      RulesetCache rulesets,
      ApiKeyCache keys) {
    this.properties = properties;
    this.instance = instance;
    this.rulesets = rulesets;
    this.keys = keys;
  }

  /** Whether this instance hears other instances' changes now. */
  public boolean isListening() {
    return listening;
  }

  @Override
  public void start() {
    synchronized (lock) {
      if (running) {
        return;
      }
      running = true;
      thread =
          Thread.ofPlatform().name("flaglane-listener").daemon().start(this::listenUntilStopped);
    }
  }

  @Override
  public void stop() {
    Thread stopping;
    synchronized (lock) {
      running = false;
      stopping = thread;
      closeQuietly(connection);
    }
    if (stopping != null) {
      stopping.interrupt();
      try {
        stopping.join(Duration.ofSeconds(5));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public boolean isRunning() {
    synchronized (lock) {
      return running;
    }
  }

  /**
   * Acts on one announcement. Never throws: a cache that cannot be brought up now is reconciled.
   */
  void handle(String payload) {
    Change change = Change.decode(payload).orElse(null);
    if (change == null) {
      LOG.debug("Ignored an announcement this instance does not understand: {}", payload);
      return;
    }
    if (change.origin().equals(instance.value())) {
      return;
    }
    try {
      switch (change.kind()) {
        case RULESET -> rulesets.rebuild(change.id());
        case KEY -> keys.reload();
      }
    } catch (RuntimeException e) {
      LOG.warn(
          "Could not apply a change announced by another instance; the next reconciliation will",
          e);
    }
  }

  private void listenUntilStopped() {
    int failures = 0;
    while (isRunning()) {
      try (Connection listen = connect()) {
        synchronized (lock) {
          if (!running) {
            return;
          }
          connection = listen;
        }
        try (Statement statement = listen.createStatement()) {
          statement.execute("LISTEN " + ChangeNotifier.CHANNEL);
        }
        catchUp();
        listening = true;
        if (failures > 0) {
          LOG.warn("Hearing other instances' changes again; caught up on what was missed");
        }
        failures = 0;
        listen(listen);
      } catch (SQLException | RuntimeException e) {
        if (!isRunning()) {
          return;
        }
        failures++;
        if (failures == 1) {
          LOG.warn(
              "Not hearing other instances' changes; reconnecting, and reconciling every minute"
                  + " meanwhile",
              e);
        }
      } finally {
        listening = false;
        synchronized (lock) {
          connection = null;
        }
      }
      pause(retryAfter(failures));
    }
  }

  private void listen(Connection listen) throws SQLException {
    PGConnection notifications = listen.unwrap(PGConnection.class);
    long probedAt = System.nanoTime();
    while (isRunning()) {
      PGNotification[] received = notifications.getNotifications(WAIT_MILLIS);
      if (received != null) {
        for (PGNotification notification : received) {
          handle(notification.getParameter());
        }
      }
      if (System.nanoTime() - probedAt >= PROBE_EVERY.toNanos()) {
        try (Statement probe = listen.createStatement()) {
          probe.execute("SELECT 1");
        }
        probedAt = System.nanoTime();
      }
    }
  }

  /** What was changed while this instance was not listening is read from the database. */
  private void catchUp() {
    rulesets.reconcile();
    keys.reload();
  }

  private Connection connect() throws SQLException {
    Properties connection = new Properties();
    connection.setProperty("user", properties.username());
    connection.setProperty("password", properties.password());
    connection.setProperty("ApplicationName", "flaglane-listener " + instance.shortForm());
    connection.setProperty("tcpKeepAlive", "true");
    connection.setProperty("connectTimeout", "10");
    connection.setProperty("socketTimeout", "60");
    return DriverManager.getConnection(properties.listenUrl(), connection);
  }

  /** Backoff with jitter from one second to thirty; none after a clean stop. */
  static Duration retryAfter(int failures) {
    if (failures <= 0) {
      return FIRST_RETRY;
    }
    long exponential = FIRST_RETRY.toMillis() << Math.min(failures - 1, 5);
    double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
    return Duration.ofMillis(Math.min(Math.round(exponential * jitter), LONGEST_RETRY.toMillis()));
  }

  private void pause(Duration wait) {
    if (!isRunning()) {
      return;
    }
    try {
      Thread.sleep(wait);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void closeQuietly(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.close();
    } catch (SQLException e) {
      LOG.debug("The listening connection did not close cleanly", e);
    }
  }
}
