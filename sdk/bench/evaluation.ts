// NFR-PER-001 for the SDK: per-call latency of FlaglaneClient.evaluate, the call an application
// makes, with 1,000 flags loaded and 50 rules on the flag under evaluation. The workload is the
// one EvaluationBenchmark builds for the server's engine (workload.ts), so the two are comparable.
//
//   npm run bench:evaluation
//
// Each call is timed on its own with performance.now(), whose cost is measured and reported beside
// the results. Every measured call takes the longest path: all 50 rules are tested, none matches,
// and the user is bucketed for the rollout. Method and results: docs/BENCHMARKS.md.

import { readFileSync } from "node:fs";
import os from "node:os";
import { performance } from "node:perf_hooks";
import { fileURLToPath } from "node:url";
import { FlaglaneClient } from "../dist/index.js";
import { FLAG, FLAGS, flagKey, nonMatchingRule, percentile, RULES, ruleCount } from "./workload.ts";

const WARM_UP = 500_000;
const SAMPLES = 1_000_000;
const ROUNDS = 3;

type Context = { key: string; country: string; plan: string; email: string; age: number; beta: boolean };

const keys = readFileSync(
  fileURLToPath(new URL("../../backend/src/test/resources/fixtures/user-keys.txt", import.meta.url)),
  "utf8",
)
  .replace(/(\r\n|\r|\n)$/, "")
  .split(/\r\n|\r|\n/);
const users: Context[] = keys.map((key, i) => ({
  key,
  country: ["LK", "IN", "GB", "US", "DE"][i % 5] as string,
  plan: i % 3 === 0 ? "pro" : "free",
  email: `user${i}@example.com`,
  age: 18 + (i % 60),
  beta: i % 2 === 0,
}));

let sink = 0;

const worst = await clientFor(RULES);
const plain = await clientFor(0);
requireLongestPath(worst);

console.log(`Node ${process.version} (V8 ${process.versions.v8}), ${os.availableParallelism()} available processors`);
console.log(
  `${FLAGS} flags, ${users.length} users, ${WARM_UP} warm-up calls, then ${ROUNDS} rounds of ${SAMPLES} timed calls`,
);
run("50 rules, none matches, then rollout", worst);
run("no rules, rollout only", plain);
timerOverhead();
console.log(`(checksum ${sink})`);
worst.close();
plain.close();

function run(name: string, client: FlaglaneClient): void {
  const samples = new Float64Array(SAMPLES);
  measure(client, new Float64Array(WARM_UP));
  for (let round = 1; round <= ROUNDS; round++) {
    measure(client, samples);
    report(`${name}, round ${round}`, samples);
  }
}

function measure(client: FlaglaneClient, samples: Float64Array): void {
  let consumed = 0;
  for (let i = 0; i < samples.length; i++) {
    const user = users[i % users.length] as Context;
    const start = performance.now();
    const evaluation = client.evaluate(FLAG, user, false);
    const end = performance.now();
    samples[i] = end - start;
    consumed += evaluation.value ? 1 : 0;
  }
  sink += consumed;
}

function timerOverhead(): void {
  const samples = new Float64Array(SAMPLES);
  for (let i = 0; i < samples.length; i++) {
    const start = performance.now();
    const end = performance.now();
    samples[i] = end - start;
  }
  report("timer alone", samples);
}

function report(name: string, samples: Float64Array): void {
  const sorted = Float64Array.from(samples).sort();
  const us = (fraction: number) => (percentile(sorted, fraction) * 1000).toFixed(2).padStart(7);
  const max = ((sorted[sorted.length - 1] as number) * 1000).toFixed(2).padStart(9);
  console.log(
    `${name.padEnd(48)} p50 ${us(0.5)} us  p95 ${us(0.95)} us  p99 ${us(0.99)} us  p99.9 ${us(0.999)} us  max ${max} us`,
  );
}

/** 1,000 flags in the served shape; the flag under evaluation has `rules` rules, the rest three. */
function ruleset(rules: number): unknown {
  const flags = [];
  for (let f = 0; f < FLAGS; f++) {
    const key = flagKey(f);
    const count = ruleCount(key, rules);
    flags.push({
      key,
      enabled: true,
      offValue: false,
      fallthroughValue: false,
      rolloutBasisPoints: 3000,
      rolloutSalt: key,
      overrides: [],
      rules: Array.from({ length: count }, (_, r) => nonMatchingRule(r)),
    });
  }
  return { environment: "production", version: 1, flags };
}

/** A client holding the ruleset, served once by a stub; it never polls again during the run. */
async function clientFor(rules: number): Promise<FlaglaneClient> {
  const body = JSON.stringify(ruleset(rules));
  return FlaglaneClient.init({
    sdkKey: "flg_srv_benchmark",
    pollIntervalMs: 3_600_000,
    fetch: async () => new Response(body, { status: 200, headers: { etag: '"1-server"' } }),
  });
}

function requireLongestPath(client: FlaglaneClient): void {
  for (const user of users) {
    const { reason } = client.evaluate(FLAG, user, false);
    if (reason !== "ROLLOUT" && reason !== "FALLTHROUGH") {
      throw new Error(`${user.key} ended at ${reason}, not after rule 50`);
    }
  }
}
