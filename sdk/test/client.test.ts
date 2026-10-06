import { afterEach, describe, expect, it } from "vitest";
import { FlaglaneClient } from "../src/index.js";
import { type ParityFixture, fixture } from "./fixtures.js";
import { type TestServer, servingRuleset, startServer, waitFor } from "./server.js";

const rulesets = fixture<ParityFixture>("evaluation-parity.json").rulesets;
const production = rulesets["production"] as { version: number; flags: unknown[] };
const quiet = { warn: () => {} };
const clients: FlaglaneClient[] = [];
const servers: TestServer[] = [];

afterEach(async () => {
  clients.splice(0).forEach((client) => client.close());
  await Promise.all(servers.splice(0).map((server) => server.close()));
});

async function setUp(ruleset: () => { version: number }, pollIntervalMs = 20) {
  const server = await startServer(servingRuleset(ruleset));
  servers.push(server);
  const client = await FlaglaneClient.init({
    sdkKey: "flg_srv_test-key",
    baseUrl: `${server.url}/`,
    pollIntervalMs,
    logger: quiet,
  });
  clients.push(client);
  return { server, client };
}

describe("the client", () => {
  it("asks for the environment's ruleset with its key", async () => {
    const { server, client } = await setUp(() => production);

    expect(client.ready).toBe(true);
    expect(server.requests[0]?.url).toBe("/sdk/config");
    expect(server.requests[0]?.headers.authorization).toBe("Bearer flg_srv_test-key");
    expect(server.requests[0]?.headers["if-none-match"]).toBeUndefined();
  });

  it("re-asks with the ETag, and an unchanged ruleset costs a 304", async () => {
    const { server, client } = await setUp(() => production);

    await waitFor(() => server.requests.length >= 3);

    expect(server.requests[1]?.headers["if-none-match"]).toBe('"7-server"');
    expect(client.version).toBe(7);
  });

  it("swaps in a changed ruleset whole", async () => {
    let current: { version: number; flags: unknown[] } = production;
    const { client } = await setUp(() => current);
    expect(client.isOn("kill-switch", { key: "user-1" }, true)).toBe(false);

    current = {
      ...production,
      version: 8,
      flags: production.flags.map((flag) =>
        (flag as { key: string }).key === "kill-switch" ? { ...(flag as object), enabled: true } : flag,
      ),
    };
    await waitFor(() => client.version === 8);

    expect(client.evaluate("kill-switch", { key: "user-1" })).toEqual({ value: true, reason: "OVERRIDE" });
  });

  it("takes the user key and attributes from one flat context", async () => {
    const { client } = await setUp(() => production);

    expect(client.evaluate("override-first", { key: "amara@example.com" })).toEqual({
      value: true,
      reason: "OVERRIDE",
    });
    expect(client.evaluate("rules-order", { key: "user-1", country: "LK" })).toEqual({
      value: true,
      reason: "RULE_MATCH",
    });
    expect(client.evaluate("rules-order", { country: "US" }, true)).toEqual({
      value: false,
      reason: "RULE_MATCH",
    });
  });

  it("evaluates anonymously without a context", async () => {
    const { client } = await setUp(() => production);

    expect(client.evaluate("anonymous-rollout")).toEqual({ value: false, reason: "FALLTHROUGH" });
    expect(client.isOn("inert-rollout")).toBe(true);
  });

  it("stops asking once closed", async () => {
    const { server, client } = await setUp(() => production);

    client.close();
    const after = server.requests.length;
    await new Promise((resolve) => setTimeout(resolve, 100));

    expect(server.requests.length).toBeLessThanOrEqual(after + 1);
    expect(client.isOn("rollout-30", { key: "user-1" })).toBe(true);
  });

  it("reads a client key's ruleset, which has no overrides", async () => {
    const client = rulesets["production-client"] as { version: number };
    const server = await startServer(servingRuleset(() => client, "client"));
    servers.push(server);
    const sdk = await FlaglaneClient.init({ sdkKey: "flg_cli_test", baseUrl: server.url, logger: quiet });
    clients.push(sdk);

    expect(sdk.evaluate("override-first", { key: "amara@example.com" }, true)).toEqual({
      value: false,
      reason: "FALLTHROUGH",
    });
    expect(sdk.evaluate("kill-switch", { key: "user-1" }, true)).toEqual({
      value: true,
      reason: "FLAG_NOT_FOUND",
    });
  });
});
