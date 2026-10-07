import { afterEach, describe, expect, it } from "vitest";
import { FlaglaneClient } from "../src/index.js";
import { type ParityFixture, fixture } from "./fixtures.js";
import { type TestServer, servingRuleset, startServer, unreachableUrl, waitFor } from "./server.js";

/**
 * Suite 8 — SDK offline behaviour (FR-SDK-005, FR-SDK-006, NFR-REL-001). Flaglane being down must
 * never break the application using it: evaluation continues on the last ruleset, a fresh start
 * with Flaglane unreachable answers with the caller's fallback, nothing throws, and startup is
 * never held beyond the init timeout.
 */

const production = fixture<ParityFixture>("evaluation-parity.json").rulesets["production"] as {
  version: number;
};
const quiet = { warn: () => {} };
const clients: FlaglaneClient[] = [];
const servers: TestServer[] = [];

afterEach(async () => {
  clients.splice(0).forEach((client) => client.close());
  await Promise.all(servers.splice(0).map((server) => server.close()));
});

async function serve(handler: Parameters<typeof startServer>[0]): Promise<TestServer> {
  const server = await startServer(handler);
  servers.push(server);
  return server;
}

async function init(options: Parameters<typeof FlaglaneClient.init>[0]): Promise<FlaglaneClient> {
  const client = await FlaglaneClient.init({ logger: quiet, ...options });
  clients.push(client);
  return client;
}

describe("suite 8: offline behaviour", () => {
  it("evaluation continues on the last ruleset once the server is unreachable", async () => {
    const server = await startServer(servingRuleset(() => production));
    let unreachable = 0;
    const counting: typeof fetch = async (input, init) => {
      try {
        return await fetch(input, init);
      } catch (error) {
        unreachable++;
        throw error;
      }
    };
    const client = await init({
      sdkKey: "flg_srv_test",
      baseUrl: server.url,
      pollIntervalMs: 10,
      maxBackoffMs: 20,
      fetch: counting,
    });
    expect(client.evaluate("rollout-30", { key: "user-1" })).toEqual({ value: true, reason: "ROLLOUT" });

    await server.close();
    await waitFor(() => unreachable >= 2);

    expect(client.ready).toBe(true);
    expect(client.evaluate("rollout-30", { key: "user-1" })).toEqual({ value: true, reason: "ROLLOUT" });
    expect(client.isOn("kill-switch", { key: "user-1" }, true)).toBe(false);
  });

  it("a start with the server unreachable answers with the caller's fallback, without throwing", async () => {
    const started = Date.now();
    const client = await init({
      sdkKey: "flg_srv_test",
      baseUrl: await unreachableUrl(),
      initTimeoutMs: 2_000,
    });

    expect(Date.now() - started).toBeLessThan(2_000);
    expect(client.ready).toBe(false);
    expect(client.isOn("rollout-30", { key: "user-1" }, true)).toBe(true);
    expect(client.isOn("rollout-30", { key: "user-1" }, false)).toBe(false);
    expect(client.evaluate("rollout-30", { key: "user-1" }, true)).toEqual({ value: true, reason: "ERROR" });
  });

  it("a server that accepts but never answers holds startup no longer than the init timeout", async () => {
    const server = await serve(() => {
      // Accept the request and never respond.
    });
    const started = Date.now();

    const client = await init({ sdkKey: "flg_srv_test", baseUrl: server.url, initTimeoutMs: 300 });

    const elapsed = Date.now() - started;
    expect(elapsed).toBeGreaterThanOrEqual(250);
    expect(elapsed).toBeLessThan(1_500);
    expect(client.isOn("rollout-30", { key: "user-1" }, true)).toBe(true);
  });

  it("a ruleset that arrives after startup gave up waiting is used from the moment it arrives", async () => {
    let respond: (() => void) | undefined;
    const server = await serve((request, response) => {
      respond = () => servingRuleset(() => production)(request, response);
    });

    const client = await init({ sdkKey: "flg_srv_test", baseUrl: server.url, initTimeoutMs: 50 });
    expect(client.ready).toBe(false);
    await waitFor(() => respond !== undefined);
    respond?.();
    await waitFor(() => client.ready);

    expect(client.isOn("rollout-30", { key: "user-1" })).toBe(true);
  });

  it("a server error keeps the last ruleset", async () => {
    let failing = false;
    const server = await serve((request, response) => {
      if (failing) {
        response.statusCode = 500;
        response.end("{}");
        return;
      }
      servingRuleset(() => production)(request, response);
    });
    const client = await init({ sdkKey: "flg_srv_test", baseUrl: server.url, pollIntervalMs: 20 });

    failing = true;
    const before = server.requests.length;
    await waitFor(() => server.requests.length > before);

    expect(client.version).toBe(7);
    expect(client.isOn("rollout-30", { key: "user-1" })).toBe(true);
  });

  it("a key over its rate limit keeps the last ruleset and waits as long as Retry-After says", async () => {
    let limited = false;
    const answeredAt: number[] = [];
    const server = await serve((request, response) => {
      answeredAt.push(performance.now());
      if (limited) {
        response.statusCode = 429;
        response.setHeader("retry-after", "1");
        response.end("{}");
        return;
      }
      servingRuleset(() => production)(request, response);
    });
    const client = await init({ sdkKey: "flg_srv_test", baseUrl: server.url, pollIntervalMs: 20 });

    limited = true;
    const before = server.requests.length;
    await waitFor(() => server.requests.length > before + 1, 5_000);
    const refusedAt = answeredAt[before] ?? 0;
    const retriedAt = answeredAt[before + 1] ?? 0;

    expect(retriedAt - refusedAt).toBeGreaterThanOrEqual(950);
    expect(client.version).toBe(7);
    expect(client.isOn("rollout-30", { key: "user-1" })).toBe(true);
  });

  it("a response that is not a ruleset never replaces the last ruleset", async () => {
    let garbage = false;
    const server = await serve((request, response) => {
      if (garbage) {
        response.setHeader("content-type", "application/json");
        response.end(request.url?.includes("x") ? "not json" : JSON.stringify({ flags: "nope" }));
        return;
      }
      servingRuleset(() => production)(request, response);
    });
    const client = await init({ sdkKey: "flg_srv_test", baseUrl: server.url, pollIntervalMs: 20 });

    garbage = true;
    const before = server.requests.length;
    await waitFor(() => server.requests.length > before + 1);

    expect(client.version).toBe(7);
    expect(client.isOn("rollout-30", { key: "user-1" })).toBe(true);
  });
});

describe("suite 8: isOn never throws", () => {
  it("whatever it is given", async () => {
    const server = await serve(servingRuleset(() => production));
    const client = await init({ sdkKey: "flg_srv_test", baseUrl: server.url });
    const hostile = {
      key: "user-1",
      get country(): string {
        throw new Error("a getter that throws");
      },
    };
    const calls: (() => unknown)[] = [
      () => client.isOn(undefined as unknown as string),
      () => client.isOn(null as unknown as string, { key: "user-1" }, true),
      () => client.isOn({} as unknown as string),
      () => client.isOn("rollout-30", null as unknown as undefined),
      () => client.isOn("rollout-30", [] as unknown as undefined),
      () => client.isOn("rollout-30", "user-1" as unknown as undefined),
      () => client.isOn("rollout-30", hostile),
      () => client.isOn("rollout-30", { key: 42 as unknown as string }),
      () => client.isOn("rollout-30", { key: "user-1" }, "yes" as unknown as boolean),
      () => client.isOn("malformed", { key: "user-1", plan: "free" }),
      () => client.isOn("x".repeat(100_000)),
    ];

    for (const call of calls) {
      expect(call).not.toThrow();
      expect(typeof call()).toBe("boolean");
    }
  });

  it("a context it cannot read answers with the fallback", async () => {
    const server = await serve(servingRuleset(() => production));
    const client = await init({ sdkKey: "flg_srv_test", baseUrl: server.url });
    const hostile = {
      key: "user-1",
      get plan(): string {
        throw new Error("a getter that throws");
      },
    };

    expect(client.evaluate("override-first", hostile, true)).toEqual({ value: true, reason: "ERROR" });
  });

  it("a logger that throws does not make evaluation throw", async () => {
    const client = await FlaglaneClient.init({
      sdkKey: "flg_srv_test",
      baseUrl: await unreachableUrl(),
      logger: {
        warn: () => {
          throw new Error("logging is down");
        },
      },
    });
    clients.push(client);

    expect(() => client.isOn("anything", { key: "user-1" }, true)).not.toThrow();
    expect(client.isOn("anything", { key: "user-1" }, true)).toBe(true);
  });

  it("init never rejects, even without a key", async () => {
    const client = await init({ sdkKey: undefined as unknown as string, baseUrl: await unreachableUrl() });

    expect(client.isOn("anything", {}, true)).toBe(true);
  });
});
