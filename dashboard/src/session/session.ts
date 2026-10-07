// The signed-in session, held in this module's memory and nowhere else (ADR-031). Not in
// localStorage or sessionStorage, which a script injected into the page could read and which
// outlive the tab: a reload, a new tab or closing the tab signs the user out, and the sign-in page
// says so. The token cannot be revoked (ADR-012), so the one place it lives is the one place it
// can be lost from.

import { useSyncExternalStore } from "react";

export interface Session {
  readonly token: string;
  readonly email: string;
  /** Milliseconds since the epoch. */
  readonly expiresAt: number;
}

/** Why the last session ended, for the sign-in page to say. */
export type EndReason = "signed-out" | "expired" | "rejected";

/** How the last session ended, and when the token it discarded would have expired. */
export interface Ending {
  readonly reason: EndReason;
  readonly tokenExpiresAt: number;
}

export interface SessionState {
  readonly session: Session | null;
  readonly ended: Ending | null;
}

type Timers = {
  readonly now: () => number;
  readonly setTimeout: (callback: () => void, ms: number) => unknown;
  readonly clearTimeout: (handle: unknown) => void;
};

const systemTimers: Timers = {
  now: () => Date.now(),
  setTimeout: (callback, ms) => globalThis.setTimeout(callback, ms),
  clearTimeout: (handle) => globalThis.clearTimeout(handle as ReturnType<typeof setTimeout>),
};

// setTimeout fires at once for a delay above 2^31 - 1 ms; a token never lives that long, but a
// clock set far wrong must not end a session the moment it starts.
const LONGEST_TIMEOUT = 2 ** 31 - 1;

export class SessionStore {
  #state: SessionState = { session: null, ended: null };
  #expiry: unknown = null;
  readonly #listeners = new Set<() => void>();
  readonly #timers: Timers;

  constructor(timers: Timers = systemTimers) {
    this.#timers = timers;
  }

  get state(): SessionState {
    return this.#state;
  }

  /** Starts a session from a sign-in response; it ends by itself when the token expires. */
  start(token: string, email: string, expiresAt: string): void {
    const at = Date.parse(expiresAt);
    if (Number.isNaN(at)) {
      throw new Error(`The API sent an expiry that is not a time: ${expiresAt}`);
    }
    this.#cancelExpiry();
    this.#set({ session: { token, email, expiresAt: at }, ended: null });
    this.#scheduleExpiry(at);
  }

  /** Discards the token. Nothing is sent to the API: there is nothing there to revoke (E-029). */
  end(reason: EndReason): void {
    const session = this.#state.session;
    if (session === null) {
      return;
    }
    this.#cancelExpiry();
    this.#set({ session: null, ended: { reason, tokenExpiresAt: session.expiresAt } });
  }

  subscribe = (listener: () => void): (() => void) => {
    this.#listeners.add(listener);
    return () => this.#listeners.delete(listener);
  };

  #scheduleExpiry(at: number): void {
    const remaining = at - this.#timers.now();
    if (remaining <= 0) {
      this.end("expired");
      return;
    }
    this.#expiry = this.#timers.setTimeout(
      () => {
        if (this.#timers.now() >= at) {
          this.end("expired");
        } else {
          this.#scheduleExpiry(at);
        }
      },
      Math.min(remaining, LONGEST_TIMEOUT),
    );
  }

  #cancelExpiry(): void {
    if (this.#expiry !== null) {
      this.#timers.clearTimeout(this.#expiry);
      this.#expiry = null;
    }
  }

  #set(state: SessionState): void {
    this.#state = state;
    for (const listener of this.#listeners) {
      listener();
    }
  }
}

/** The dashboard's one session. */
export const sessions = new SessionStore();

export function useSession(): SessionState {
  return useSyncExternalStore(sessions.subscribe, () => sessions.state);
}
