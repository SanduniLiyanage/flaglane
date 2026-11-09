// Checks the exit criteria of milestones 4 and 5 against a running Flaglane and a running shop.
//
//   npm run verify -- propagation   the shop's SDK holds a stream, and a rollout change and the kill
//                                   switch each reach the shop within a second; raising the rollout
//                                   takes the new checkout away from nobody who had it
//   npm run verify -- polling       the same with a shop started with FLAGLANE_STREAMING=false: no
//                                   stream, and every change within the five-second poll interval
//   npm run verify -- offline       run after stopping Flaglane: the shop keeps serving, from the
//                                   last ruleset it had rather than from fallbacks
//
// CI runs all three against docker compose. propagation and polling change the demo's flags and
// leave new-checkout on at 60%; npm run seed puts it back to 30%.

import assert from "node:assert/strict";
import { setTimeout as sleep } from "node:timers/promises";
import { type Config, Management } from "./management.ts";
import { ENVIRONMENT, NEW_CHECKOUT, account, flaglaneUrl, projectKey, sdkKey, shopUrl } from "./settings.ts";

const POLL_INTERVAL_MS = 5_000;
/** One conditional request on top of the interval, and this script sampling every 50 ms. */
const ALLOWANCE_MS = 1_000;
/** Milestone 5's bound with a stream: an event, one download, and this script's sampling. */
const STREAMED_MS = 1_000;

interface Evaluation {
  readonly value: boolean;
  readonly reason: string;
}

interface State {
  readonly newCheckout: Evaluation;
  readonly freeShipping: Evaluation;
  readonly crowdOn: readonly string[];
  readonly sdk: {
    readonly ready: boolean;
    readonly version?: number;
    readonly streaming: boolean;
    readonly lastMessage?: { readonly message: string; readonly at: string };
  };
}

const mode = process.argv[2];

try {
  if (mode === "propagation") {
    await propagation(true, STREAMED_MS);
  } else if (mode === "polling") {
    await propagation(false, POLL_INTERVAL_MS + ALLOWANCE_MS);
  } else if (mode === "offline") {
    await offline();
  } else {
    throw new Error("Usage: npm run verify -- propagation | polling | offline");
  }
} catch (error) {
  console.error(error instanceof Error ? (error.stack ?? error.message) : error);
  process.exitCode = 1;
}

/** Four changes, each timed from the moment Flaglane has it to the moment the shop serves it. */
async function propagation(streamed: boolean, budgetMs: number): Promise<void> {
  const { email, password } = account();
  const api = await Management.signIn(email, password);
  await until("the shop to hold a ruleset", 30_000, async () => ((await state()).sdk.ready ? true : undefined));
  if (streamed) {
    await until("the shop's SDK to hold a stream", 30_000, async () => ((await state()).sdk.streaming ? true : undefined));
  } else {
    assert.equal((await state()).sdk.streaming, false, "the shop was started with FLAGLANE_STREAMING=false");
  }

  const start = await change(api, { enabled: true, rolloutPercentage: 30 });
  const raised = await change(api, { rolloutPercentage: 60 });
  const lost = start.state.crowdOn.filter((key) => !raised.state.crowdOn.includes(key));
  assert.deepEqual(lost, [], "raising the rollout took the new checkout away from these visitors");
  assert.ok(raised.state.crowdOn.length > start.state.crowdOn.length, "raising the rollout reached more visitors");

  const killed = await change(api, { enabled: false });
  assert.deepEqual(killed.state.crowdOn, [], "the kill switch turns the flag off for every visitor");
  assert.deepEqual(killed.state.newCheckout, { value: false, reason: "OFF" }, "the kill switch beats an override");

  const revived = await change(api, { enabled: true });
  assert.deepEqual(revived.state.crowdOn, raised.state.crowdOn, "switching back on restores the same visitors");
  assert.deepEqual(revived.state.newCheckout, { value: true, reason: "OVERRIDE" });

  const changes = [
    ["rollout set to 30%", start],
    ["rollout raised to 60%", raised],
    ["kill switch", killed],
    ["switched back on", revived],
  ] as const;
  for (const [what, { elapsedMs, version }] of changes) {
    console.log(`${what.padEnd(22)} version ${version} reached the shop in ${(elapsedMs / 1000).toFixed(2)} s`);
  }
  console.log(
    `Visitors with the new checkout: ${start.state.crowdOn.length} at 30%, ${raised.state.crowdOn.length} at 60%, ` +
      `none lost; 0 with the kill switch; the same ${revived.state.crowdOn.length} once back on.`,
  );
  for (const [what, { elapsedMs }] of changes) {
    assert.ok(
      elapsedMs <= budgetMs,
      `${what} took ${elapsedMs.toFixed(0)} ms to reach the shop ${streamed ? "over its stream" : "by polling"}; ` +
        `the bound is ${budgetMs} ms`,
    );
  }
  console.log(streamed ? "All four arrived over the stream." : "All four arrived by polling, with no stream.");
}

async function offline(): Promise<void> {
  const reachable = await fetch(`${flaglaneUrl}/actuator/health`).then(
    () => true,
    () => false,
  );
  assert.equal(reachable, false, `Flaglane still answers at ${flaglaneUrl}; stop it before running this check`);

  // The SDK logs once when polling starts failing and once when it recovers, so a last message
  // saying it cannot reach Flaglane means it is failing now.
  const qa = await until("the shop's SDK to notice Flaglane is unreachable", 20_000, async () => {
    const current = await state("qa-tester");
    return current.sdk.lastMessage?.message.includes("could not be reached") ? current : undefined;
  });
  const amara = await state("amara");

  assert.ok(qa.sdk.ready && qa.sdk.version !== undefined, "the shop still holds a ruleset");
  assert.deepEqual(qa.newCheckout, { value: true, reason: "OVERRIDE" }, "overrides still apply");
  assert.deepEqual(amara.freeShipping, { value: true, reason: "RULE_MATCH" }, "rules still apply");
  assert.ok(amara.crowdOn.length > 0, "the rollout still applies");
  const page = await fetch(`${shopUrl}/?shopper=qa-tester`);
  assert.equal(page.status, 200);
  assert.match(await page.text(), /Express checkout/);

  console.log(
    `Flaglane is unreachable and the shop is serving ruleset version ${qa.sdk.version}: qa-tester's override, ` +
      `the LK rule for amara, and ${amara.crowdOn.length} of 100 visitors in the rollout all still apply.`,
  );
}

/** Makes one change, then times how long the shop takes to serve the ruleset version it produced. */
async function change(api: Management, body: Partial<Config>): Promise<{ elapsedMs: number; version: number; state: State }> {
  await api.call("PATCH", `/api/projects/${projectKey}/flags/${NEW_CHECKOUT}/config/${ENVIRONMENT}`, body);
  const changedAt = performance.now();
  // The server rebuilds its ruleset cache before the PATCH returns, so this is the new version.
  const version = await servedVersion();
  const current = await until(`the shop to serve version ${version}`, POLL_INTERVAL_MS * 3, async () => {
    const latest = await state();
    return (latest.sdk.version ?? -1) >= version ? latest : undefined;
  });
  return { elapsedMs: performance.now() - changedAt, version, state: current };
}

async function servedVersion(): Promise<number> {
  const response = await fetch(`${flaglaneUrl}/sdk/config`, { headers: { authorization: `Bearer ${sdkKey()}` } });
  assert.equal(response.status, 200, "the demo's SDK key reads the ruleset");
  return ((await response.json()) as { version: number }).version;
}

async function state(shopper = "qa-tester"): Promise<State> {
  const response = await fetch(`${shopUrl}/state.json?shopper=${shopper}`);
  assert.equal(response.status, 200, "the shop answers");
  return (await response.json()) as State;
}

/** Polls until the probe returns something; a probe that throws counts as not yet. */
async function until<T>(what: string, timeoutMs: number, probe: () => Promise<T | undefined>): Promise<T> {
  const deadline = performance.now() + timeoutMs;
  let lastError: unknown;
  for (;;) {
    try {
      const result = await probe();
      if (result !== undefined) {
        return result;
      }
    } catch (error) {
      lastError = error;
    }
    if (performance.now() > deadline) {
      throw new Error(`Timed out after ${timeoutMs} ms waiting for ${what}`, { cause: lastError });
    }
    await sleep(50);
  }
}
