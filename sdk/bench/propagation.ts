// NFR-PER-003: how long a change made through the management API takes to reach an SDK polling at
// its default interval, five seconds.
//
//   npm run bench:propagation      against FLAGLANE_URL, default http://localhost:8080
//
// Each sample waits a random time first, so changes land at every point of the SDK's poll cycle
// rather than just after a poll, then times from the PATCH returning to the SDK holding the ruleset
// version the change produced. The server rebuilds its cache before the PATCH returns, so that
// version is the one GET /sdk/config serves straight afterwards. Method and results:
// docs/BENCHMARKS.md.

import { setTimeout as sleep } from "node:timers/promises";
import { FlaglaneClient } from "../dist/index.js";
import { baseUrl, benchmarkEnvironment, ENVIRONMENT, servedVersion } from "./stack.ts";
import { FLAG, reportMillis } from "./workload.ts";

const SAMPLES = Number(process.env.SAMPLES ?? 100);
const POLL_INTERVAL_MS = 5_000;

const { api, project, sdkKey } = await benchmarkEnvironment();
const client = await FlaglaneClient.init({ sdkKey, baseUrl });
if (!client.ready) {
  throw new Error("The SDK did not get a ruleset at start");
}

const samples: number[] = [];
for (let i = 0; i < SAMPLES; i++) {
  await sleep(Math.random() * POLL_INTERVAL_MS);
  await api.call("PATCH", `/api/projects/${project}/flags/${FLAG}/config/${ENVIRONMENT}`, {
    rolloutPercentage: i % 2 === 0 ? 60 : 30,
  });
  const changedAt = performance.now();
  const version = await servedVersion(sdkKey);
  while ((client.version ?? -1) < version) {
    await sleep(1);
  }
  samples.push(performance.now() - changedAt);
  if ((i + 1) % 10 === 0) {
    console.log(`${i + 1} of ${SAMPLES} changes picked up`);
  }
}
client.close();
reportMillis(`change to SDK, ${POLL_INTERVAL_MS / 1000} s polling`, samples);
