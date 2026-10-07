// NFR-PER-002: GET /sdk/config served from the ruleset cache, timed as a client sees it, from the
// request leaving to the last byte of the body arriving and decoded, over a kept-alive connection.
// fetch asks for gzip and decodes it, as the SDK does, so this times the compressed answer.
//
//   npm run bench:serving          against FLAGLANE_URL, default http://localhost:8080
//
// Five measurements on the shared workload's 1,000-flag environment: full answers one at a time,
// 304s one at a time (what a polling SDK gets while nothing changes), then full answers and 304s
// from 16 clients at once, the 304s showing what the concurrency costs without any body, and the
// full answers once more without decoding them, which separates the client's work from the server's. Run it from
// a container on the stack's network, FLAGLANE_URL=http://api:8080, to leave out the host's port
// forwarding. RULESET=varied builds the same shape with random keys and values, which
// compresses about as badly as a ruleset can (stack.ts). Method and results: docs/BENCHMARKS.md.

import http from "node:http";
import os from "node:os";
import { baseUrl, benchmarkEnvironment } from "./stack.ts";
import { reportMillis } from "./workload.ts";

const WARM_UP = 500;
const SEQUENTIAL = 5_000;
const CONCURRENT_CLIENTS = 16;
const PER_CLIENT = 500;

const varied = process.env.RULESET === "varied";
const { sdkKey } = await benchmarkEnvironment({ varied });
const authorization = `Bearer ${sdkKey}`;

const first = await fetch(`${baseUrl}/sdk/config`, { headers: { authorization } });
const body = await first.arrayBuffer();
const etag = first.headers.get("etag") ?? "";
// fetch asks for gzip itself and decodes it, as the SDK does; Content-Length is what was sent.
const coding = first.headers.get("content-encoding") ?? "identity";
const sent = Number(first.headers.get("content-length") ?? body.byteLength);
console.log(
  `Node ${process.version}, ${os.availableParallelism()} available processors; ${baseUrl}; ${varied ? "varied" : "generated"} ruleset`,
);
console.log(
  `Full answer ${(body.byteLength / 1024).toFixed(0)} KiB, sent as ${coding} in ${(sent / 1024).toFixed(0)} KiB, ETag ${etag}`,
);

for (let i = 0; i < WARM_UP; i++) {
  await timed({});
  await timed({ "if-none-match": etag });
}
reportMillis("200, one client", await sequence({}));
reportMillis("304, one client", await sequence({ "if-none-match": etag }));
const all = await Promise.all(Array.from({ length: CONCURRENT_CLIENTS }, () => sequence({}, PER_CLIENT)));
reportMillis(`200, ${CONCURRENT_CLIENTS} clients at once`, all.flat());
// The same concurrency with no body: what any request on this path costs, ruleset or not.
const empty = await Promise.all(
  Array.from({ length: CONCURRENT_CLIENTS }, () => sequence({ "if-none-match": etag }, PER_CLIENT)),
);
reportMillis(`304, ${CONCURRENT_CLIENTS} clients at once`, empty.flat());
// The same full answers again, each client on its own connection, timed to the last byte of the
// body as sent and left undecoded: what the server and the network cost, without the decoding that
// sixteen clients sharing this one process would otherwise queue behind each other.
const undecoded = await Promise.all(
  Array.from({ length: CONCURRENT_CLIENTS }, async () => {
    const agent = new http.Agent({ keepAlive: true, maxSockets: 1 });
    const samples: number[] = [];
    for (let i = 0; i < PER_CLIENT; i++) {
      samples.push(await undecodedAnswer(agent));
    }
    agent.destroy();
    return samples;
  }),
);
reportMillis(`200, ${CONCURRENT_CLIENTS} clients at once, not decoded`, undecoded.flat());

async function sequence(headers: Record<string, string>, count = SEQUENTIAL): Promise<number[]> {
  const samples: number[] = [];
  for (let i = 0; i < count; i++) {
    samples.push(await timed(headers));
  }
  return samples;
}

/** One full answer, read to its last byte as sent, and not decoded. */
function undecodedAnswer(agent: http.Agent): Promise<number> {
  const start = performance.now();
  return new Promise((resolve, reject) => {
    const request = http.get(
      `${baseUrl}/sdk/config`,
      { agent, headers: { authorization, "accept-encoding": "gzip" } },
      (response) => {
        response.on("data", () => {});
        response.on("error", reject);
        response.on("end", () => {
          if (response.statusCode === 200) {
            resolve(performance.now() - start);
          } else {
            reject(new Error(`GET /sdk/config answered ${response.statusCode}, not 200`));
          }
        });
      },
    );
    request.on("error", reject);
  });
}

async function timed(headers: Record<string, string>): Promise<number> {
  const start = performance.now();
  const response = await fetch(`${baseUrl}/sdk/config`, { headers: { authorization, ...headers } });
  await response.arrayBuffer();
  const elapsed = performance.now() - start;
  const expected = headers["if-none-match"] === undefined ? 200 : 304;
  if (response.status !== expected) {
    throw new Error(`GET /sdk/config answered ${response.status}, not ${expected}`);
  }
  return elapsed;
}
