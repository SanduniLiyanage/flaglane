// NFR-PER-003: how long a change made through the management API takes to reach a running SDK —
// over its stream, as the SDK runs by default, or with STREAMING=false by polling every five
// seconds.
//
//   npm run bench:propagation                  over the stream, against FLAGLANE_URL
//   STREAMING=false npm run bench:propagation  by polling
//
// Each sample waits a random time first, so changes land at every point of a poll cycle rather than
// just after a poll, then times from the PATCH being sent to the SDK holding the ruleset version the
// change produced: the commit, the server's rebuild, the event or the next poll, and the download,
// everything between a person's change and an application using it. Each write moves the
// environment's version on by one, so the version to wait for is known before the timing starts,
// and checked against the server after it ends. Method and results: docs/BENCHMARKS.md.

import { setTimeout as sleep } from "node:timers/promises";
import { FlaglaneClient } from "../dist/index.js";
import { baseUrl, benchmarkEnvironment, ENVIRONMENT, servedVersion } from "./stack.ts";
import { FLAG, reportMillis } from "./workload.ts";

const SAMPLES = Number(process.env.SAMPLES ?? 100);
const STREAMING = (process.env.STREAMING ?? "true").toLowerCase() !== "false";
const POLL_INTERVAL_MS = 5_000;

const { api, project, sdkKey } = await benchmarkEnvironment();
const client = await FlaglaneClient.init({ sdkKey, baseUrl, streaming: STREAMING });
if (!client.ready) {
  throw new Error("The SDK did not get a ruleset at start");
}
if (STREAMING) {
  await until(() => client.streaming, 30_000, "the SDK to open its stream");
}

const samples: number[] = [];
const roundTrips: number[] = [];
for (let i = 0; i < SAMPLES; i++) {
  await sleep(Math.random() * POLL_INTERVAL_MS);
  if (STREAMING && !client.streaming) {
    throw new Error(`The SDK lost its stream before change ${i + 1}; this run measures nothing`);
  }
  const expected = (await servedVersion(sdkKey)) + 1;
  const sentAt = performance.now();
  await api.call("PATCH", `/api/projects/${project}/flags/${FLAG}/config/${ENVIRONMENT}`, {
    rolloutPercentage: i % 2 === 0 ? 60 : 30,
  });
  roundTrips.push(performance.now() - sentAt);
  while ((client.version ?? -1) < expected) {
    await sleep(1);
  }
  samples.push(performance.now() - sentAt);
  const served = await servedVersion(sdkKey);
  if (served !== expected || client.version !== expected) {
    throw new Error(`Change ${i + 1} expected version ${expected}; the server serves ${served}, the SDK ${client.version}`);
  }
  if ((i + 1) % 10 === 0) {
    console.log(`${i + 1} of ${SAMPLES} changes picked up`);
  }
}
client.close();
const how = STREAMING ? "over its stream" : `${POLL_INTERVAL_MS / 1000} s polling`;
reportMillis(`change to SDK, ${how}`, samples);
reportMillis("PATCH round trip, for scale", roundTrips);

async function until(condition: () => boolean, timeoutMs: number, what: string): Promise<void> {
  const deadline = performance.now() + timeoutMs;
  while (!condition()) {
    if (performance.now() > deadline) {
      throw new Error(`Timed out after ${timeoutMs} ms waiting for ${what}`);
    }
    await sleep(10);
  }
}
