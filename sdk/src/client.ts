import { userFrom, userFromContext } from "./context.js";
import { Evaluator } from "./evaluator.js";
import { type Ruleset, parseRuleset } from "./ruleset.js";
import { EventStreamParser, type StreamEvent } from "./stream.js";
import type { Evaluation, Logger } from "./types.js";

export interface FlaglaneOptions {
  /** A server or client key for one environment: `flg_srv_…` or `flg_cli_…`. */
  readonly sdkKey: string;
  /** Where Flaglane serves `/sdk/**`. Defaults to `http://localhost:8080`. */
  readonly baseUrl?: string;
  /**
   * How long `init` waits for the first ruleset before resolving without one. Defaults to 3000.
   * Startup is never blocked for longer, whatever the state of the network (FR-SDK-001).
   */
  readonly initTimeoutMs?: number;
  /** How often to ask whether the ruleset changed. Defaults to 5000 (ADR-026). */
  readonly pollIntervalMs?: number;
  /** The longest the SDK waits between attempts while Flaglane is unreachable. Defaults to 30000. */
  readonly maxBackoffMs?: number;
  /** How long one request may take before it is abandoned. Defaults to 10000. */
  readonly requestTimeoutMs?: number;
  /** The `fetch` to use. Defaults to the global one. */
  readonly fetch?: typeof globalThis.fetch;
  /** Where to report problems. Defaults to `console.warn`. */
  readonly logger?: Logger;
  /**
   * Whether to hold a stream open to Flaglane, so that a change arrives within moments instead of at
   * the next poll. Defaults to true. While a stream is open and nothing it announced is missing, the
   * SDK does not poll; without one, it polls exactly as it would with this false. In Node an open
   * stream, like any open connection, keeps the process running: call `close()` when the process
   * should exit, or set this to false for a short-lived script (ADR-040).
   */
  readonly streaming?: boolean;
  /**
   * How long a stream may stay silent before the SDK takes it for dead, polls, and reconnects.
   * Defaults to 60000; Flaglane sends something at least every 30 seconds.
   */
  readonly streamIdleTimeoutMs?: number;
}

/**
 * Who a flag is evaluated for: an optional `key`, which drives overrides and percentage rollouts,
 * and any attributes rules can target. Attributes that are not a string, finite number or boolean
 * are ignored. Without a key, evaluation is anonymous: rules still apply.
 */
export interface Context {
  readonly key?: string | null | undefined;
  readonly [attribute: string]: unknown;
}

type Outcome =
  | { readonly kind: "updated" }
  | { readonly kind: "unchanged" }
  | { readonly kind: "failed"; readonly retryAfterMs?: number };

/** How a stream ended: by the server after it worked, by failing, or because there is none. */
type StreamEnd =
  | { readonly kind: "ended" }
  | { readonly kind: "failed"; readonly retryAfterMs?: number }
  | { readonly kind: "unsupported" };

/** The event Flaglane sends when an environment's ruleset moves to a newer version (docs/API.md). */
const RULESET_CHANGED = "ruleset-changed";

const consoleLogger: Logger = {
  warn: (message, error) => console.warn(message, ...(error === undefined ? [] : [error])),
};

/**
 * Feature flags evaluated in process, from the environment's ruleset (ADR-002).
 *
 * The ruleset is downloaded once. After that a stream tells the SDK the moment a newer one exists,
 * and the SDK downloads it with a conditional request; whenever there is no stream, the SDK asks
 * every few seconds instead, which costs nothing while the ruleset has not changed (ADR-026,
 * ADR-040). Evaluating a flag never touches the network, and if Flaglane cannot be reached the SDK
 * keeps answering from the last ruleset it had — and, if it never had one, with the fallback the
 * caller passed. It never throws from `isOn` or `evaluate`, and `init` never rejects: Flaglane being
 * down must not break the application using it.
 */
export class FlaglaneClient {
  private readonly url: string;
  private readonly streamUrl: string;
  private readonly sdkKey: string;
  private readonly pollIntervalMs: number;
  private readonly maxBackoffMs: number;
  private readonly requestTimeoutMs: number;
  private readonly initTimeoutMs: number;
  private readonly fetchImpl: typeof globalThis.fetch;
  private readonly logger: Logger;
  private readonly evaluator: Evaluator;
  private readonly streamIdleTimeoutMs: number;
  private streamingEnabled: boolean;

  private ruleset: Ruleset | undefined;
  private etag: string | undefined;
  private failures = 0;
  private closed = false;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private inFlight: AbortController | undefined;
  private current: Promise<Outcome> | undefined;
  private queued: Promise<Outcome> | undefined;
  private stream: AbortController | undefined;
  private streamOpen = false;
  private streamFailures = 0;
  private announced: number | undefined;
  private wake: (() => void) | undefined;

  private constructor(options: FlaglaneOptions) {
    this.sdkKey = typeof options.sdkKey === "string" ? options.sdkKey : "";
    const base = (options.baseUrl ?? "http://localhost:8080").replace(/\/+$/, "");
    this.url = `${base}/sdk/config`;
    this.streamUrl = `${base}/sdk/stream`;
    this.pollIntervalMs = positive(options.pollIntervalMs, 5_000);
    this.maxBackoffMs = Math.max(positive(options.maxBackoffMs, 30_000), this.pollIntervalMs);
    this.requestTimeoutMs = positive(options.requestTimeoutMs, 10_000);
    this.initTimeoutMs = positive(options.initTimeoutMs, 3_000);
    this.fetchImpl = options.fetch ?? globalThis.fetch.bind(globalThis);
    this.logger = options.logger ?? consoleLogger;
    this.evaluator = new Evaluator({ logger: this.logger });
    this.streamIdleTimeoutMs = positive(options.streamIdleTimeoutMs, 60_000);
    this.streamingEnabled = options.streaming !== false && this.sdkKey !== "";
  }

  /**
   * Fetches the ruleset, then resolves (FR-SDK-001). If the first ruleset has not arrived within
   * `initTimeoutMs`, resolves anyway, and every flag answers with its caller's fallback until one
   * does. Never rejects.
   */
  static async init(options: FlaglaneOptions): Promise<FlaglaneClient> {
    const client = new FlaglaneClient(options);
    if (client.sdkKey === "") {
      safeWarn(client.logger, "Flaglane: no sdkKey given; every flag will return its fallback");
    }
    await client.start();
    return client;
  }

  /**
   * Whether the flag is on for this user, evaluated in process with no network call (FR-SDK-002).
   * Never throws (FR-SDK-006).
   *
   * @param fallback what to answer when the flag cannot be evaluated: no ruleset yet, an unknown
   *     flag, a flag this key may not read. Choose the value that is safe if Flaglane is down.
   */
  isOn(flagKey: string, context?: Context, fallback = false): boolean {
    return this.evaluate(flagKey, context, fallback).value;
  }

  /** As `isOn`, with the reason the value was chosen. Never throws. */
  evaluate(flagKey: string, context?: Context, fallback = false): Evaluation {
    const safeFallback = fallback === true;
    try {
      const user =
        context !== null && typeof context === "object"
          ? userFromContext(context)
          : userFrom(undefined, undefined);
      return this.evaluator.evaluate(this.ruleset, flagKey, user, safeFallback);
    } catch (error) {
      safeWarn(this.logger, "Flaglane: a context could not be read; returned the fallback", error);
      return { value: safeFallback, reason: "ERROR" };
    }
  }

  /** Whether a ruleset has arrived. Until one has, every flag returns its fallback. */
  get ready(): boolean {
    return this.ruleset !== undefined;
  }

  /** The `ruleset_version` of the ruleset in use, if any. */
  get version(): number | undefined {
    return this.ruleset?.version;
  }

  /** Whether a stream to Flaglane is open now. Without one, the SDK polls. */
  get streaming(): boolean {
    return this.streamOpen;
  }

  /** Stops polling and ends the stream. Evaluation keeps working on the last ruleset. */
  close(): void {
    this.closed = true;
    if (this.timer !== undefined) {
      clearTimeout(this.timer);
    }
    this.inFlight?.abort();
    this.stream?.abort();
    this.wake?.();
  }

  private async start(): Promise<void> {
    let timeout: ReturnType<typeof setTimeout> | undefined;
    const timedOut = new Promise<void>((resolve) => {
      timeout = unref(setTimeout(resolve, this.initTimeoutMs));
    });
    const first = this.refreshNow().then((outcome) => {
      this.schedule(outcome);
      if (this.streamingEnabled) {
        void this.runStream();
      }
    });
    await Promise.race([first, timedOut]);
    clearTimeout(timeout);
  }

  /**
   * Polls on a timer. While a stream is open and the SDK holds every version it has announced, the
   * timer fires without asking: the stream will say when there is something to fetch.
   */
  private schedule(outcome: Outcome): void {
    if (this.closed) {
      return;
    }
    this.timer = unref(
      setTimeout(() => {
        if (this.upToDateByStream()) {
          this.schedule({ kind: "unchanged" });
          return;
        }
        void this.refreshNow().then((next) => this.schedule(next));
      }, this.delayAfter(outcome)),
    );
  }

  private upToDateByStream(): boolean {
    return (
      this.streamOpen &&
      this.ruleset !== undefined &&
      this.announced !== undefined &&
      this.ruleset.version >= this.announced
    );
  }

  /**
   * One refresh at a time. Asked for while one runs, another follows it, and every caller asking
   * meanwhile shares that one: a poll and an event arriving together cost one request, not two.
   */
  private refreshNow(): Promise<Outcome> {
    if (this.queued !== undefined) {
      return this.queued;
    }
    if (this.current === undefined) {
      return this.startRefresh();
    }
    const queued = this.current.then(() => {
      this.queued = undefined;
      return this.startRefresh();
    });
    this.queued = queued;
    return queued;
  }

  private startRefresh(): Promise<Outcome> {
    const run: Promise<Outcome> = this.refresh().finally(() => {
      if (this.current === run) {
        this.current = undefined;
      }
    });
    this.current = run;
    return run;
  }

  /** Holds a stream open, reconnecting as long as the client lives and the server streams. */
  private async runStream(): Promise<void> {
    while (!this.closed && this.streamingEnabled) {
      const end = await this.streamOnce();
      if (end.kind === "unsupported") {
        this.streamingEnabled = false;
        safeWarn(
          this.logger,
          `Flaglane: this server does not stream changes; polling every ${this.pollIntervalMs} ms`,
        );
        return;
      }
      if (this.closed) {
        return;
      }
      await this.pause(this.streamDelayAfter(end));
    }
  }

  /**
   * One stream, from request to end. Never throws. An event naming a version newer than the one held
   * fetches it at once; anything else in the stream only shows it is alive.
   */
  private async streamOnce(): Promise<StreamEnd> {
    const controller = new AbortController();
    this.stream = controller;
    let idle: ReturnType<typeof setTimeout> | undefined;
    let quiet = false;
    let heard = false;
    const stillAlive = () => {
      clearTimeout(idle);
      idle = unref(
        setTimeout(() => {
          quiet = true;
          controller.abort();
        }, this.streamIdleTimeoutMs),
      );
    };
    try {
      const response = await this.openStream(controller);
      if (response.status === 404 || (response.ok && !isEventStream(response))) {
        await discard(response);
        return { kind: "unsupported" };
      }
      if (!response.ok || response.body === null) {
        await discard(response);
        const retryAfter = Number(response.headers.get("retry-after"));
        return this.streamFailed(
          `the change stream answered ${response.status}`,
          undefined,
          (response.status === 503 || response.status === 429) &&
            Number.isFinite(retryAfter) &&
            retryAfter > 0
            ? retryAfter * 1000
            : undefined,
        );
      }
      this.streamOpen = true;
      stillAlive();
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      const parser = new EventStreamParser();
      for (;;) {
        const { done, value } = await reader.read();
        if (done) {
          break;
        }
        stillAlive();
        for (const event of parser.push(decoder.decode(value, { stream: true }))) {
          heard = true;
          this.heard(event);
        }
      }
      return heard ? { kind: "ended" } : this.streamFailed("the change stream ended at once");
    } catch (error) {
      if (this.closed) {
        return { kind: "ended" };
      }
      return this.streamFailed(
        quiet ? "the change stream went quiet" : "the change stream was lost",
        quiet ? undefined : error,
      );
    } finally {
      clearTimeout(idle);
      this.streamOpen = false;
      controller.abort();
      if (this.stream === controller) {
        this.stream = undefined;
      }
    }
  }

  /** The stream's response, or a rejection if its headers take longer than a request may. */
  private async openStream(controller: AbortController): Promise<Response> {
    const timeout = unref(setTimeout(() => controller.abort(), this.requestTimeoutMs));
    try {
      return await this.fetchImpl(this.streamUrl, {
        headers: { authorization: `Bearer ${this.sdkKey}`, accept: "text/event-stream" },
        signal: controller.signal,
      });
    } finally {
      clearTimeout(timeout);
    }
  }

  private heard(event: StreamEvent): void {
    if (this.streamFailures > 0) {
      safeWarn(this.logger, "Flaglane: the change stream is back");
    }
    this.streamFailures = 0;
    if (event.event !== RULESET_CHANGED) {
      return;
    }
    const version = announcedVersion(event);
    if (version === undefined) {
      return;
    }
    if (this.announced === undefined || version > this.announced) {
      this.announced = version;
    }
    if (this.ruleset === undefined || version > this.ruleset.version) {
      void this.refreshNow();
    }
  }

  private streamFailed(message: string, error?: unknown, retryAfterMs?: number): StreamEnd {
    this.streamFailures++;
    if (this.streamFailures === 1 && !this.closed) {
      safeWarn(this.logger, `Flaglane: ${message}; polling for changes meanwhile`, error);
    }
    return retryAfterMs === undefined ? { kind: "failed" } : { kind: "failed", retryAfterMs };
  }

  /**
   * The wait before reconnecting. A stream the server ended after it worked, as it does when a
   * stream reaches its lifetime, is replaced within a second, jittered so a fleet does not return
   * at one instant. After a failure, exponential backoff with jitter up to `maxBackoffMs`, and never
   * sooner than a `Retry-After`: polling continues meanwhile, so waiting costs no change.
   */
  private streamDelayAfter(end: StreamEnd): number {
    if (end.kind === "ended") {
      return Math.random() * 1_000;
    }
    const exponential = this.pollIntervalMs * 2 ** Math.min(this.streamFailures - 1, 16);
    const backoff = Math.min(exponential * (0.8 + Math.random() * 0.4), this.maxBackoffMs);
    return end.kind === "failed" && end.retryAfterMs !== undefined
      ? Math.max(end.retryAfterMs, backoff)
      : backoff;
  }

  /** Waits, unless the client is closed meanwhile. Never keeps a Node process alive. */
  private pause(ms: number): Promise<void> {
    return new Promise<void>((resolve) => {
      const timer = unref(setTimeout(resolve, ms));
      this.wake = () => {
        clearTimeout(timer);
        resolve();
      };
    }).finally(() => {
      this.wake = undefined;
    });
  }

  /**
   * The wait before the next attempt: the poll interval while things work; after failures,
   * exponential backoff with jitter up to `maxBackoffMs`, so a fleet of applications does not
   * return to a recovering server in lockstep (FR-SDK-004); the `Retry-After` of a 503, or of a 429
   * when the key is over its rate limit, when given.
   */
  private delayAfter(outcome: Outcome): number {
    if (outcome.kind !== "failed") {
      return this.pollIntervalMs;
    }
    if (outcome.retryAfterMs !== undefined) {
      return Math.min(Math.max(outcome.retryAfterMs, this.pollIntervalMs), this.maxBackoffMs);
    }
    const exponential = this.pollIntervalMs * 2 ** Math.min(this.failures - 1, 16);
    const jitter = 0.8 + Math.random() * 0.4;
    return Math.min(exponential * jitter, this.maxBackoffMs);
  }

  /** One conditional request for the ruleset. Never throws; a bad answer never replaces a ruleset. */
  private async refresh(): Promise<Outcome> {
    if (this.closed) {
      return { kind: "unchanged" };
    }
    const controller = new AbortController();
    this.inFlight = controller;
    const timeout = unref(setTimeout(() => controller.abort(), this.requestTimeoutMs));
    try {
      const headers: Record<string, string> = {
        authorization: `Bearer ${this.sdkKey}`,
        accept: "application/json",
      };
      if (this.etag !== undefined) {
        headers["if-none-match"] = this.etag;
      }
      const response = await this.fetchImpl(this.url, { headers, signal: controller.signal });
      if (response.status === 304) {
        return this.succeeded({ kind: "unchanged" });
      }
      if (response.ok) {
        const ruleset = parseRuleset(await response.json());
        if (ruleset === undefined) {
          return this.failed("Flaglane served something that is not a ruleset");
        }
        this.ruleset = ruleset;
        this.etag = response.headers.get("etag") ?? undefined;
        return this.succeeded({ kind: "updated" });
      }
      const retryAfter = Number(response.headers.get("retry-after"));
      return this.failed(
        `Flaglane answered ${response.status}`,
        undefined,
        (response.status === 503 || response.status === 429) && Number.isFinite(retryAfter) && retryAfter > 0
          ? retryAfter * 1000
          : undefined,
      );
    } catch (error) {
      return this.failed("Flaglane could not be reached", error);
    } finally {
      clearTimeout(timeout);
      if (this.inFlight === controller) {
        this.inFlight = undefined;
      }
    }
  }

  private succeeded(outcome: Outcome): Outcome {
    if (this.failures > 0) {
      safeWarn(this.logger, "Flaglane: reachable again; ruleset updates resume");
    }
    this.failures = 0;
    return outcome;
  }

  private failed(message: string, error?: unknown, retryAfterMs?: number): Outcome {
    this.failures++;
    if (this.failures === 1 && !this.closed) {
      const serving = this.ruleset === undefined ? "flags return their fallbacks" : "serving the last ruleset";
      safeWarn(this.logger, `Flaglane: ${message}; ${serving} and retrying`, error);
    }
    return retryAfterMs === undefined ? { kind: "failed" } : { kind: "failed", retryAfterMs };
  }
}

/** The version an event announces: its data's `version`, or failing that its id. */
function announcedVersion(event: StreamEvent): number | undefined {
  try {
    const data: unknown = JSON.parse(event.data);
    if (data !== null && typeof data === "object") {
      const version = (data as { version?: unknown }).version;
      if (typeof version === "number" && Number.isSafeInteger(version) && version >= 0) {
        return version;
      }
    }
  } catch {
    // Not JSON: the id may still say.
  }
  const id = event.id === undefined ? Number.NaN : Number(event.id);
  return Number.isSafeInteger(id) && id >= 0 ? id : undefined;
}

function isEventStream(response: Response): boolean {
  return (response.headers.get("content-type") ?? "").toLowerCase().startsWith("text/event-stream");
}

/** Lets go of a response whose body will not be read. */
async function discard(response: Response): Promise<void> {
  try {
    await response.body?.cancel();
  } catch {
    // Nothing to release.
  }
}

function positive(value: number | undefined, otherwise: number): number {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : otherwise;
}

/** In Node, a timer that must never keep the application's process alive on its own. */
function unref<T>(timer: T): T {
  (timer as { unref?: () => void }).unref?.();
  return timer;
}

function safeWarn(logger: Logger, message: string, error?: unknown): void {
  try {
    logger.warn(message, error);
  } catch {
    // Reporting a problem must never become one.
  }
}
