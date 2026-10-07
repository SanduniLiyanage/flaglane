import { userFrom, userFromContext } from "./context.js";
import { Evaluator } from "./evaluator.js";
import { type Ruleset, parseRuleset } from "./ruleset.js";
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

const consoleLogger: Logger = {
  warn: (message, error) => console.warn(message, ...(error === undefined ? [] : [error])),
};

/**
 * Feature flags evaluated in process, from the environment's ruleset (ADR-002).
 *
 * The ruleset is downloaded once, then re-checked every few seconds with a conditional request that
 * costs nothing while it has not changed. Evaluating a flag never touches the network, and if
 * Flaglane cannot be reached the SDK keeps answering from the last ruleset it had — and, if it never
 * had one, with the fallback the caller passed. It never throws from `isOn` or `evaluate`, and
 * `init` never rejects: Flaglane being down must not break the application using it.
 */
export class FlaglaneClient {
  private readonly url: string;
  private readonly sdkKey: string;
  private readonly pollIntervalMs: number;
  private readonly maxBackoffMs: number;
  private readonly requestTimeoutMs: number;
  private readonly initTimeoutMs: number;
  private readonly fetchImpl: typeof globalThis.fetch;
  private readonly logger: Logger;
  private readonly evaluator: Evaluator;

  private ruleset: Ruleset | undefined;
  private etag: string | undefined;
  private failures = 0;
  private closed = false;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private inFlight: AbortController | undefined;

  private constructor(options: FlaglaneOptions) {
    this.sdkKey = typeof options.sdkKey === "string" ? options.sdkKey : "";
    this.url = `${(options.baseUrl ?? "http://localhost:8080").replace(/\/+$/, "")}/sdk/config`;
    this.pollIntervalMs = positive(options.pollIntervalMs, 5_000);
    this.maxBackoffMs = Math.max(positive(options.maxBackoffMs, 30_000), this.pollIntervalMs);
    this.requestTimeoutMs = positive(options.requestTimeoutMs, 10_000);
    this.initTimeoutMs = positive(options.initTimeoutMs, 3_000);
    this.fetchImpl = options.fetch ?? globalThis.fetch.bind(globalThis);
    this.logger = options.logger ?? consoleLogger;
    this.evaluator = new Evaluator({ logger: this.logger });
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

  /** Stops polling. Evaluation keeps working on the last ruleset. */
  close(): void {
    this.closed = true;
    if (this.timer !== undefined) {
      clearTimeout(this.timer);
    }
    this.inFlight?.abort();
  }

  private async start(): Promise<void> {
    let timeout: ReturnType<typeof setTimeout> | undefined;
    const timedOut = new Promise<void>((resolve) => {
      timeout = unref(setTimeout(resolve, this.initTimeoutMs));
    });
    const first = this.refresh().then((outcome) => {
      this.schedule(outcome);
    });
    await Promise.race([first, timedOut]);
    clearTimeout(timeout);
  }

  private schedule(outcome: Outcome): void {
    if (this.closed) {
      return;
    }
    this.timer = unref(
      setTimeout(() => {
        void this.refresh().then((next) => this.schedule(next));
      }, this.delayAfter(outcome)),
    );
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
