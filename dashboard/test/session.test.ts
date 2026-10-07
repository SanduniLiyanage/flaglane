import { describe, expect, it } from "vitest";
import { SessionStore } from "../src/session/session";

/** Timers driven by hand, so a test moves time instead of waiting for it. */
function manualTimers(start: number) {
  let now = start;
  let next = 0;
  const pending = new Map<number, { at: number; callback: () => void }>();
  return {
    now: () => now,
    setTimeout: (callback: () => void, ms: number) => {
      pending.set(++next, { at: now + ms, callback });
      return next;
    },
    clearTimeout: (handle: unknown) => {
      pending.delete(handle as number);
    },
    advance(ms: number) {
      now += ms;
      for (const [handle, timer] of [...pending]) {
        if (timer.at <= now) {
          pending.delete(handle);
          timer.callback();
        }
      }
    },
    get pending() {
      return pending.size;
    },
  };
}

const START = Date.parse("2026-10-07T09:00:00Z");

describe("a session", () => {
  it("holds the token it was started with", () => {
    const store = new SessionStore(manualTimers(START));

    store.start("eyJ.token", "amara@example.com", "2026-10-07T17:00:00Z");

    expect(store.state.session).toEqual({
      token: "eyJ.token",
      email: "amara@example.com",
      expiresAt: Date.parse("2026-10-07T17:00:00Z"),
    });
    expect(store.state.ended).toBeNull();
  });

  it("ends by itself when the token expires, and says so", () => {
    const timers = manualTimers(START);
    const store = new SessionStore(timers);
    store.start("eyJ.token", "amara@example.com", "2026-10-07T17:00:00Z");

    timers.advance(8 * 60 * 60 * 1000 - 1);
    expect(store.state.session).not.toBeNull();
    timers.advance(1);

    expect(store.state.session).toBeNull();
    expect(store.state.ended?.reason).toBe("expired");
  });

  it("is discarded on sign-out, which remembers until when a copied token would stay valid", () => {
    const timers = manualTimers(START);
    const store = new SessionStore(timers);
    store.start("eyJ.token", "amara@example.com", "2026-10-07T17:00:00Z");

    store.end("signed-out");

    expect(store.state.session).toBeNull();
    expect(store.state.ended).toEqual({ reason: "signed-out", tokenExpiresAt: Date.parse("2026-10-07T17:00:00Z") });
    expect(timers.pending).toBe(0);
  });

  it("that has already expired when it starts ends at once", () => {
    const store = new SessionStore(manualTimers(START));

    store.start("eyJ.token", "amara@example.com", "2026-10-07T08:59:59Z");

    expect(store.state.session).toBeNull();
    expect(store.state.ended?.reason).toBe("expired");
  });

  it("tells its listeners when it starts and ends", () => {
    const store = new SessionStore(manualTimers(START));
    const seen: Array<string | null> = [];
    store.subscribe(() => seen.push(store.state.session?.token ?? null));

    store.start("eyJ.token", "amara@example.com", "2026-10-07T17:00:00Z");
    store.end("rejected");
    store.end("rejected");

    expect(seen).toEqual(["eyJ.token", null]);
  });
});
