import { afterEach, describe, expect, it } from "vitest";
import { FlaglaneClient, type FlaglaneOptions } from "../src/index.js";
import { type ParityFixture, fixture } from "./fixtures.js";
import {
  type StreamingServer,
  rulesetRequests,
  servingRuleset,
  startServer,
  startStreamingServer,
  type TestServer,
  waitFor,
} from "./server.js";

/**
 * The SDK on the stream (FR-SDK-003, FR-SDK-004, ADR-040): a change is fetched the moment the
 * stream announces it; while the stream is open and nothing it announced is missing, the SDK does
 * not poll; whenever there is no stream it polls as before, and nothing about the stream ever
 * throws or replaces a ruleset with anything but a ruleset.
 */

const production = fixture<ParityFixture>("evaluation-parity.json").rulesets["production"] as {
  version: number;
  flags: unknown[];
};
const quiet = { warn: () => {} };
const clients: FlaglaneClient[] = [];
const servers: TestServer[] = [];

afterEach(async () => {
  clients.splice(0).forEach((client) => client.close());
  await Promise.all(servers.splice(0).map((server) => server.close()));
});

async function streaming(
  ruleset: () => { version: number },
  onStream?: Parameters<typeof startStreamingServer>[1],
): Promise<StreamingServer> {
  const server = await startStreamingServer(ruleset, onStream);
  servers.push(server);
  return server;
}

async function init(options: Omit<FlaglaneOptions, "sdkKey">): Promise<FlaglaneClient> {
  const client = await FlaglaneClient.init({ logger: quiet, sdkKey: "flg_srv_test", ...options });
  clients.push(client);
  return client;
}

/** The ruleset at version 8, with the kill switch turned on. */
const changedRuleset = {
  ...production,
  version: 8,
  flags: production.flags.map((flag) =>
    (flag as { key: string }).key === "kill-switch" ? { ...(flag as object), enabled: true } : flag,
  ),
};

function pause(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

describe("the stream", () => {
  it("fetches a change the moment it is announced, not at the next poll", async () => {
    let current: { version: number } = production;
    const server = await streaming(() => current);
    const client = await init({ baseUrl: server.url, pollIntervalMs: 60_000 });
    await waitFor(() => client.streaming);
    expect(client.isOn("kill-switch", { key: "user-1" }, true)).toBe(false);

    current = changedRuleset;
    server.announce(8);

    await waitFor(() => client.version === 8, 1_000);
    expect(client.isOn("kill-switch", { key: "user-1" })).toBe(true);
  });

  it("does not poll while the stream is open and the ruleset is the one announced", async () => {
    const server = await streaming(() => production);
    const client = await init({ baseUrl: server.url, pollIntervalMs: 20 });
    await waitFor(() => client.streaming);
    const before = rulesetRequests(server).length;

    await pause(300);

    expect(rulesetRequests(server).length).toBe(before);
    expect(client.version).toBe(7);
  });

  it("polls when the server ends the stream, and opens another", async () => {
    const server = await streaming(() => production);
    const client = await init({ baseUrl: server.url, pollIntervalMs: 20 });
    await waitFor(() => client.streaming);
    const polled = rulesetRequests(server).length;

    server.endStreams();

    await waitFor(() => server.streamRequests() >= 2 && client.streaming, 3_000);
    expect(rulesetRequests(server).length).toBeGreaterThan(polled);
  });

  it("polls as before against a server with no stream, and says so once", async () => {
    const warnings: string[] = [];
    const server = await streaming(
      () => production,
      (_request, response) => {
        response.statusCode = 404;
        response.end();
        return true;
      },
    );
    const client = await init({
      baseUrl: server.url,
      pollIntervalMs: 20,
      logger: { warn: (message) => warnings.push(message) },
    });

    await waitFor(() => rulesetRequests(server).length >= 4);

    expect(server.streamRequests()).toBe(1);
    expect(client.streaming).toBe(false);
    expect(warnings.filter((message) => message.includes("does not stream"))).toHaveLength(1);
  });

  it("polls as before when the stream's answer is not an event stream", async () => {
    const server = await startServer(servingRuleset(() => production));
    servers.push(server);
    const client = await init({ baseUrl: server.url, pollIntervalMs: 20 });

    await waitFor(() => rulesetRequests(server).length >= 4);

    expect(server.requests.filter((request) => request.url === "/sdk/stream")).toHaveLength(1);
    expect(client.streaming).toBe(false);
  });

  it("asks again for a refused stream no sooner than its Retry-After, polling meanwhile", async () => {
    const refusedAt: number[] = [];
    const server = await streaming(
      () => production,
      (_request, response) => {
        refusedAt.push(performance.now());
        response.statusCode = 429;
        response.setHeader("retry-after", "1");
        response.end("{}");
        return true;
      },
    );
    await init({ baseUrl: server.url, pollIntervalMs: 20, maxBackoffMs: 40 });
    const polledBefore = rulesetRequests(server).length;

    await waitFor(() => refusedAt.length >= 2, 4_000);

    expect((refusedAt[1] ?? 0) - (refusedAt[0] ?? 0)).toBeGreaterThanOrEqual(950);
    expect(rulesetRequests(server).length).toBeGreaterThan(polledBefore + 5);
  });

  it("drops a stream that goes quiet, polls, and opens another", async () => {
    const server = await streaming(
      () => production,
      (_request, response) => {
        response.writeHead(200, { "content-type": "text/event-stream" });
        response.flushHeaders();
        return true;
      },
    );
    await init({ baseUrl: server.url, pollIntervalMs: 20, streamIdleTimeoutMs: 100 });
    const polledBefore = rulesetRequests(server).length;

    await waitFor(() => server.streamRequests() >= 2, 3_000);

    expect(rulesetRequests(server).length).toBeGreaterThan(polledBefore);
  });

  it("ends its stream when closed", async () => {
    const server = await streaming(() => production);
    const client = await init({ baseUrl: server.url, pollIntervalMs: 20 });
    await waitFor(() => server.open.size === 1);

    client.close();

    await waitFor(() => server.open.size === 0);
    expect(client.streaming).toBe(false);
  });

  it("is never opened with streaming off", async () => {
    const server = await streaming(() => production);
    const client = await init({ baseUrl: server.url, pollIntervalMs: 20, streaming: false });

    await waitFor(() => rulesetRequests(server).length >= 3);

    expect(server.streamRequests()).toBe(0);
    expect(client.streaming).toBe(false);
  });

  it("never throws on events it cannot read, and keeps the ruleset it has", async () => {
    const server = await streaming(() => production);
    const client = await init({ baseUrl: server.url, pollIntervalMs: 60_000 });
    await waitFor(() => client.streaming);

    server.send("event: ruleset-changed\ndata: not json\n\n");
    server.send('event: ruleset-changed\ndata: {"version":"eight"}\n\n');
    server.send("event: something-else\ndata: {}\n\n");
    server.send(":heartbeat\n\n");
    await pause(100);

    expect(client.streaming).toBe(true);
    expect(client.version).toBe(7);
    expect(client.evaluate("rollout-30", { key: "user-1" })).toEqual({ value: true, reason: "ROLLOUT" });
  });

  it("keeps evaluating on the last ruleset when the server goes away mid-stream", async () => {
    const server = await streaming(() => production);
    const client = await init({ baseUrl: server.url, pollIntervalMs: 20, maxBackoffMs: 40 });
    await waitFor(() => client.streaming);

    await server.close();
    servers.splice(servers.indexOf(server), 1);
    await waitFor(() => !client.streaming);

    expect(client.version).toBe(7);
    expect(client.evaluate("rollout-30", { key: "user-1" })).toEqual({ value: true, reason: "ROLLOUT" });
  });
});
