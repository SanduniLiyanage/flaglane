package io.github.sanduniliyanage.flaglane.streaming.service;

import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKeyEvents;
import io.github.sanduniliyanage.flaglane.apikey.domain.SdkCredential;
import io.github.sanduniliyanage.flaglane.common.errors.TooManyRequestsException;
import io.github.sanduniliyanage.flaglane.serving.domain.RulesetSwapped;
import io.github.sanduniliyanage.flaglane.serving.service.RulesetCache;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * Every open {@code GET /sdk/stream}, by environment and by key (FR-SRV-003, FR-STR-001 to
 * FR-STR-004, ADR-039).
 *
 * <ul>
 *   <li>A stream's first event carries its environment's current version, so an SDK that reconnects
 *       learns at once whether it missed a change while it was away.
 *   <li>When the ruleset cache moves an environment to a newer version, every stream of that
 *       environment, and of no other, gets a {@code ruleset-changed} event naming the version.
 *       Never the ruleset: the SDK downloads that with its ETag, exactly as when it polls.
 *   <li>Every stream gets a comment each heartbeat, so a proxy that closes quiet connections leaves
 *       it open, and a client that has gone away is found by the failed write.
 *   <li>Revoking a key closes its streams before the revoke returns.
 *   <li>A stream ends after {@code maxDuration}, and its SDK reconnects.
 *   <li>At shutdown every stream is ended first, so the server's graceful shutdown does not wait on
 *       connections that would never finish by themselves.
 * </ul>
 *
 * <p>Events and heartbeats are written by one thread of the registry's own, in the order they were
 * raised: the request that made a change never waits on a subscriber, and no stream gets an older
 * version after a newer one. Everything here is in memory and per instance (ADR-036).
 */
@Component
public class StreamRegistry implements SmartLifecycle, DisposableBean {

  /** The event's name, which SDKs listen for (docs/API.md). */
  public static final String EVENT = "ruleset-changed";

  /** Seconds a refused stream is told to wait. Its SDK polls meanwhile, so nothing is missed. */
  static final long RETRY_AFTER_SECONDS = 60;

  private static final Logger LOG = LoggerFactory.getLogger(StreamRegistry.class);

  private final StreamProperties properties;
  private final RulesetCache cache;
  private final ExecutorService writer =
      Executors.newSingleThreadExecutor(
          Thread.ofPlatform().name("flaglane-streams").daemon().factory());

  private final Object lock = new Object();
  private final Map<UUID, Set<Stream>> byEnvironment = new HashMap<>();
  private final Map<UUID, Integer> openPerKey = new HashMap<>();
  private boolean running;

  public StreamRegistry(StreamProperties properties, RulesetCache cache) {
    this.properties = properties;
    this.cache = cache;
  }

  /** What a {@code ruleset-changed} event carries. */
  public record Change(String environment, long version) {}

  private record Stream(SdkCredential key, SseEmitter emitter) {}

  /**
   * Opens a stream for the key, or empty while the server is not accepting streams, as during
   * shutdown.
   *
   * @throws TooManyRequestsException if the key, or its environment, already holds as many streams
   *     as it may
   */
  public Optional<SseEmitter> open(SdkCredential key) {
    Stream stream = new Stream(key, new SseEmitter(properties.maxDuration().toMillis()));
    synchronized (lock) {
      if (!running) {
        return Optional.empty();
      }
      if (openPerKey.getOrDefault(key.keyId(), 0) >= properties.maxPerKey()) {
        throw new TooManyRequestsException(
            "Too many open streams with this API key; poll for changes meanwhile",
            RETRY_AFTER_SECONDS);
      }
      if (byEnvironment.getOrDefault(key.environmentId(), Set.of()).size()
          >= properties.maxPerEnvironment()) {
        throw new TooManyRequestsException(
            "Too many open streams for this environment; poll for changes meanwhile",
            RETRY_AFTER_SECONDS);
      }
      byEnvironment.computeIfAbsent(key.environmentId(), id -> new HashSet<>()).add(stream);
      openPerKey.merge(key.keyId(), 1, Integer::sum);
    }
    SseEmitter emitter = stream.emitter();
    // Called for every ending: completed, timed out, or failed.
    emitter.onCompletion(() -> release(stream));
    emitter.onError(error -> release(stream));
    emitter.onTimeout(emitter::complete);
    if (!write(() -> sendCurrentVersion(stream))) {
      release(stream);
      return Optional.empty();
    }
    return Optional.of(emitter);
  }

  /** Tells the environment's streams that a newer version is served (FR-STR-001). */
  @EventListener
  public void swapped(RulesetSwapped event) {
    write(
        () -> {
          for (Stream stream : streamsOf(event.environmentId())) {
            send(stream, () -> changed(event.environmentKey(), event.version()));
          }
        });
  }

  /** A comment on every stream (FR-STR-002). Runs every heartbeat; tests call it directly. */
  @Scheduled(
      fixedDelayString = "${flaglane.streaming.heartbeat}",
      initialDelayString = "${flaglane.streaming.heartbeat}")
  public void heartbeat() {
    write(
        () -> {
          for (Stream stream : allStreams()) {
            send(stream, () -> SseEmitter.event().comment("heartbeat"));
          }
        });
  }

  /** A revoked key's streams end before the revoke returns (FR-STR-003). */
  @TransactionalEventListener
  public void revoked(ApiKeyEvents.Revoked event) {
    UUID keyId = event.credential().keyId();
    List<Stream> closing =
        allStreams().stream().filter(s -> s.key().keyId().equals(keyId)).toList();
    closing.forEach(
        stream -> {
          release(stream);
          end(stream);
        });
  }

  /** Open streams, every key's together. */
  public int size() {
    synchronized (lock) {
      return byEnvironment.values().stream().mapToInt(Set::size).sum();
    }
  }

  @Override
  public void start() {
    synchronized (lock) {
      running = true;
    }
  }

  /** Ends every stream and refuses new ones, before the web server begins its graceful shutdown. */
  @Override
  public void stop() {
    List<Stream> open;
    synchronized (lock) {
      running = false;
      open = allStreamsLocked();
      byEnvironment.clear();
      openPerKey.clear();
    }
    open.forEach(this::end);
  }

  @Override
  public boolean isRunning() {
    synchronized (lock) {
      return running;
    }
  }

  @Override
  public void destroy() {
    writer.shutdownNow();
  }

  static SseEventBuilder changed(String environmentKey, long version) {
    return SseEmitter.event()
        .name(EVENT)
        .id(Long.toString(version))
        .data(new Change(environmentKey, version), MediaType.APPLICATION_JSON);
  }

  private void sendCurrentVersion(Stream stream) {
    cache
        .get(stream.key().environmentId())
        .ifPresent(
            snapshot -> send(stream, () -> changed(snapshot.environmentKey(), snapshot.version())));
  }

  /**
   * An event builder is changed by sending it, so each stream is given one of its own. A stream
   * whose client has gone away fails the write and is forgotten; the container ends the request.
   */
  private void send(Stream stream, Supplier<SseEventBuilder> event) {
    try {
      stream.emitter().send(event.get());
    } catch (IOException | IllegalStateException e) {
      release(stream);
      LOG.debug("A stream ended while being written to", e);
    }
  }

  private void end(Stream stream) {
    try {
      stream.emitter().complete();
    } catch (RuntimeException e) {
      LOG.debug("A stream could not be ended cleanly", e);
    }
  }

  /** Runs on the writer thread; false once the registry is shutting down. */
  private boolean write(Runnable task) {
    try {
      writer.execute(task);
      return true;
    } catch (RejectedExecutionException e) {
      return false;
    }
  }

  private void release(Stream stream) {
    synchronized (lock) {
      Set<Stream> environment = byEnvironment.get(stream.key().environmentId());
      if (environment == null || !environment.remove(stream)) {
        return;
      }
      if (environment.isEmpty()) {
        byEnvironment.remove(stream.key().environmentId());
      }
      openPerKey.computeIfPresent(stream.key().keyId(), (id, open) -> open > 1 ? open - 1 : null);
    }
  }

  private List<Stream> streamsOf(UUID environmentId) {
    synchronized (lock) {
      return new ArrayList<>(byEnvironment.getOrDefault(environmentId, Set.of()));
    }
  }

  private List<Stream> allStreams() {
    synchronized (lock) {
      return allStreamsLocked();
    }
  }

  private List<Stream> allStreamsLocked() {
    List<Stream> all = new ArrayList<>();
    byEnvironment.values().forEach(all::addAll);
    return all;
  }
}
