import type { Logger } from "./types.js";

const INTERVAL_MS = 60_000;
const MAX_TRACKED_KEYS = 10_000;
/** Flag keys are at most 63 characters; one more marks a truncation. */
const MAX_KEY_LENGTH = 64;

/**
 * At most one warning per flag key per minute, as on the server (FR-EVL-006): a broken flag on a
 * hot path must not become a log line per call. Flag keys come from callers, so what is tracked is
 * bounded: keys are truncated and at most 10,000 are remembered. Writing a warning never throws.
 */
export class Warnings {
  private readonly lastWarnedAt = new Map<string, number>();

  constructor(
    private readonly logger: Logger,
    private readonly clock: () => number,
  ) {}

  warn(flagKey: unknown, message: string, error?: unknown): void {
    try {
      const shown = printable(flagKey);
      if (this.tryAcquire(shown)) {
        this.logger.warn(`Flaglane: flag '${shown}' ${message}`, error);
      }
    } catch {
      // A logger that fails must not turn a handled problem into an exception for the caller.
    }
  }

  private tryAcquire(key: string): boolean {
    const now = this.clock();
    const previous = this.lastWarnedAt.get(key);
    if (previous !== undefined && now - previous < INTERVAL_MS && now >= previous) {
      return false;
    }
    if (previous === undefined && this.lastWarnedAt.size >= MAX_TRACKED_KEYS) {
      for (const [tracked, at] of this.lastWarnedAt) {
        if (now - at >= INTERVAL_MS || now < at) {
          this.lastWarnedAt.delete(tracked);
        }
      }
      if (this.lastWarnedAt.size >= MAX_TRACKED_KEYS) {
        return false;
      }
    }
    this.lastWarnedAt.set(key, now);
    return true;
  }
}

/** The flag key, truncated and stripped of control characters, safe for a log line. */
function printable(flagKey: unknown): string {
  if (typeof flagKey !== "string") {
    return `<${flagKey === null ? "null" : typeof flagKey}>`;
  }
  const shown = flagKey.length > MAX_KEY_LENGTH ? `${flagKey.slice(0, MAX_KEY_LENGTH)}...` : flagKey;
  return shown.replace(/[\u0000-\u001f\u007f-\u009f]/g, "?");
}
