# Benchmarks

A number without a method is marketing. Every row below records how it was produced, on what
hardware, and against which commit, or it does not go in.

## What is measured

| Requirement | Measurement | Target | Slice |
| --- | --- | --- | --- |
| NFR-PER-001 | In-process evaluation, 1,000 flags loaded, 50 rules on the flag under evaluation | p99 under 25 µs | 2.7 |
| NFR-PER-002 | `GET /sdk/config` served from cache | p99 under 50 ms | 2.8 |
| NFR-PER-003 | A change through the management API to a polling SDK using it | within 5 s, the poll interval (E-039) | 4.10 |

NFR-PER-001's budget was tightened from 1 ms during the specification review (erratum E-013). A
hash and a map lookup cannot approach a millisecond, so the original target was satisfied by
construction and measuring it proved nothing.

## Results

Measured on 2026-10-07, on the machine and under the conditions in [Method](#method): first against
commit `f4097c9`, then `GET /sdk/config` again against `8b20628`, after the ruleset was compressed
(ADR-033). The earlier rows stay as the record of what compression changed. A range runs from the
lowest to the highest figure across every timed round or run.

| Date | Commit | Measurement | p50 | p95 | p99 | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-10-07 | `f4097c9` | NFR-PER-001, server engine: 50 rules, none matching, then the rollout | 0.5 µs | 0.7–0.8 µs | 1.0–1.4 µs | Meets the target. 9 rounds of 1,000,000 calls |
| 2026-10-07 | `f4097c9` | NFR-PER-001, server engine: no rules, rollout only | 0.1 µs | 0.2–0.4 µs | 0.3–0.7 µs | For scale |
| 2026-10-07 | `f4097c9` | NFR-PER-001, SDK: 50 rules, none matching, then the rollout | 1.1–1.3 µs | 1.3–2.3 µs | 2.4–3.9 µs | Meets the target. 9 rounds of 1,000,000 calls |
| 2026-10-07 | `f4097c9` | NFR-PER-001, SDK: no rules, rollout only | 0.3–0.4 µs | 0.5–0.8 µs | 1.5–1.9 µs | For scale |
| 2026-10-07 | `f4097c9` | NFR-PER-002, `GET /sdk/config`, full answer (506 KiB), one client | 6.1–7.0 ms | 14.6–18.2 ms | 27.3–30.2 ms | Meets the target. 2 runs of 5,000 requests |
| 2026-10-07 | `f4097c9` | NFR-PER-002, `GET /sdk/config`, `304`, one client | 3.8–4.5 ms | 5.1–7.1 ms | 6.3–10.0 ms | What a polling SDK gets while nothing changes. 2 runs of 5,000 requests |
| 2026-10-07 | `f4097c9` | NFR-PER-002, `GET /sdk/config`, full answer, 16 clients at once | 33.2–34.9 ms | 51.2–63.5 ms | 78.7–93.9 ms | **Above the 50 ms target.** 2 runs of 8,000 requests; see below |
| 2026-10-07 | `f4097c9` | NFR-PER-003, a change through the management API to an SDK polling every 5 s | 1.98 s | 4.63 s | 4.93 s | Meets the target. 100 changes; the longest took 4.94 s |
| 2026-10-07 | `8b20628` | NFR-PER-002, full answer gzipped, one client: generated ruleset, 506 KiB sent as 8 KiB | 2.7–3.2 ms | 5.6–6.3 ms | 7.7–8.7 ms | Meets the target. 2 runs of 5,000 requests |
| 2026-10-07 | `8b20628` | NFR-PER-002, full answer gzipped, one client: varied ruleset, 530 KiB sent as 81–82 KiB | 4.1–4.5 ms | 7.1–7.9 ms | 8.6–9.8 ms | Meets the target. 2 runs of 5,000 requests |
| 2026-10-07 | `8b20628` | NFR-PER-002, `304`, one client | 3.7–4.1 ms | 4.8–5.3 ms | 5.6–6.2 ms | Both rulesets. 4 runs of 5,000 requests |
| 2026-10-07 | `8b20628` | NFR-PER-002, full answer gzipped, 16 clients at once: generated ruleset | 23.7–39.0 ms | 33.4–54.1 ms | 43.0–62.3 ms | **Above the 50 ms target in one run of two.** 2 runs of 8,000 requests; see below |
| 2026-10-07 | `8b20628` | NFR-PER-002, full answer gzipped, 16 clients at once: varied ruleset | 38.7–42.3 ms | 55.2–55.7 ms | 64.9–65.5 ms | **Above the 50 ms target.** 2 runs of 8,000 requests; see below |

**NFR-PER-001 holds for both implementations, with room to spare.** The server's engine answers the
worst case in about a microsecond at the 99th percentile and the SDK in about three, against a budget
of 25. Beyond the 99th percentile, p99.9 was 5 to 9 µs on the server and 6 to 32 µs in the SDK,
and each run had a few single calls of milliseconds, the longest 20.9 ms. An evaluation does not
take milliseconds; those are pauses of the runtime or the operating system, which a per-call timer
charges to whichever call they interrupt.

**These figures depend on the conditions, and earlier ones did not meet the budget.** Measured
during development on the same laptop, pinned the same way but on battery and with other Docker
containers running, the SDK's worst case was p99 40–45 µs, and 27–32 µs after the per-call
allocations were removed (`07aa231`). Those runs are not recorded as results, because they do not
follow the method below. Changing those two conditions moved the SDK's p99 from about 30 µs to about
3, so a machine that is throttled or busy will see figures in between.

**NFR-PER-002 holds for one client, and as measured still does not hold for sixteen at once.** The
requirement names no concurrency. Before compression, one client received the full 506 KiB ruleset of
the 1,000-flag environment with p99 27 to 30 ms, and sixteen at once with p99 79 to 94 ms. Since the
ruleset is gzipped once when it is built (ADR-033), one client receives it with p99 8 to 10 ms,
whether its keys and values repeat or are random, and sixteen at once with p99 43 to 65 ms: lower,
and still above 50 ms in three runs of four. The row stays above target.

**What remains is mostly the measuring process decoding sixteen answers at once.** The harness runs
its sixteen clients in one Node process, which decodes every answer it receives, 506 or 530 KiB each,
on one thread. Timed instead to the last byte as sent, undecoded, the same sixteen clients take p99
16 to 30 ms, and from a container on the stack's own network, without Docker Desktop's forwarding
from the host, 8 to 14 ms:

| Sixteen clients at once, `8b20628` | Generated ruleset, p99 | Varied ruleset, p99 |
| --- | --- | --- |
| Full answer, decoded, from the host (the row above) | 43.0–62.3 ms | 64.9–65.5 ms |
| Full answer, decoded, from the stack's network | 42.2–42.7 ms | 47.6–60.3 ms |
| Full answer, undecoded, from the host | 15.8–19.5 ms | 26.1–29.6 ms |
| Full answer, undecoded, from the stack's network | 7.7–9.3 ms | 11.9–14.0 ms |
| `304`, from the host | 13.2–17.7 ms | 18.6–22.0 ms |
| `304`, from the stack's network | 4.4–6.0 ms | 5.0–8.4 ms |

So of the p99 the requirement's row records, the server and the network account for 8 to 14 ms, the
forwarding from the Windows host into Docker's virtual machine for about as much again, and the
difference between decoded and undecoded, roughly 25 to 50 ms, is one client process decoding
sixteen rulesets together. Percentiles do not subtract exactly; the split is approximate. An SDK decodes one ruleset, in
its own process, when the ruleset changes, and the one-client row includes that decoding. The
harness is left measuring as it did, so the row stays comparable with the one before compression,
and the decomposition is given beside it rather than in its place. In normal operation most polls
are `304`s with no body.

**NFR-PER-003 holds.** Every one of 100 changes reached the polling SDK within the five-second
interval, the slowest in 4.94 s. The spread is the poll cycle itself: a change waits for the SDK's
next poll, so its pickup time is close to uniform between nothing and five seconds, and the median
of two seconds is that, not a property of the server.

## Method

### Machine and conditions

- **Hardware:** an ASUS Vivobook 15 (X1504VA) laptop with an Intel Core i7-1355U: 10 cores, 2
  performance cores with Hyper-Threading and 8 efficiency cores, 12 logical processors; 15.6 GiB of
  memory.
- **Operating system:** Windows 11 Home 10.0.26200, power plan Balanced.
- **Power:** on mains power for every measurement. Windows reported the AC line online and the
  battery charging throughout.
- **Load:** Docker containers unrelated to Flaglane were stopped, and nothing else was started. The
  desktop session stayed open, with a browser and an editor idle in it.
- **Commit:** `f4097c9`, from a clean checkout of it (a `git worktree`), so nothing uncommitted was
  measured.

### NFR-PER-001: in-process evaluation

- **Harnesses:** `./gradlew :backend:evaluationBenchmark` (`EvaluationBenchmark`) for the server's
  engine and `npm run bench:evaluation` (`sdk/bench/evaluation.ts`) for the SDK, which times
  `FlaglaneClient.evaluate`, the call an application makes, against the built package.
- **Runtimes:** OpenJDK 64-Bit Server VM 25+36-3489, the Gradle toolchain's JDK, with a fixed 1 GiB
  heap and the default collector; Node v24.12.0 with V8 13.6.233.17.
- **Pinning:** each harness ran on logical processors 0–3, the two performance cores, through
  `start /affinity F`. The benchmark JVM's affinity mask, read during a run, was `0xF`. The
  server harness prints "12 available processors" all the same, because this JDK on Windows does
  not reflect the affinity mask in `availableProcessors()`; Node reports 4.
- **Workload:** the same in both, `sdk/bench/workload.ts` transcribing `EvaluationBenchmark`: 1,000
  enabled flags rolled out to 30% with three rules each, and on the flag under evaluation 50 rules
  cycling through all seven operators, the list operators holding ten values, that no user matches.
  The users are the 100,000 keys of `backend/src/test/resources/fixtures/user-keys.txt`, each with
  five attributes. Before timing, each harness checks that every user takes the longest path, past
  all 50 rules to the rollout. Both print the same checksum, 2,093,210, the number of calls answered
  `true` over the whole run, so both implementations gave the same answers while being timed.
- **Timing:** every call is timed on its own, with `System.nanoTime()` or `performance.now()`, so
  the percentiles are of single evaluations rather than of batch averages. 500,000 warm-up calls,
  then three rounds of 1,000,000 timed calls per workload; percentiles are nearest-rank over each
  round's million samples. Each harness ran three times, so each figure above spans nine rounds.
- **Resolution:** both timers advance in 100 ns steps on this machine (Windows'
  `QueryPerformanceCounter` runs at 10 MHz), so every figure is a multiple of 0.1 µs and those
  under about half a microsecond are dominated by that step. The timer timed against itself gave
  p99 0.1 µs.

### NFR-PER-002: `GET /sdk/config`

- **Stack:** `docker compose up --build` from the commit, under a project name of its own with
  credentials generated for the run, and removed afterwards with its volume. The API image runs
  Eclipse Temurin 25.0.4+7, against PostgreSQL 16.15, in Docker Desktop 29.1.3, whose WSL 2 virtual
  machine had 12 processors and 7.6 GiB. Neither was pinned.
- **Harness:** `npm run bench:serving` (`sdk/bench/serving.ts`) on the host, Node v24.12.0,
  unpinned, against `http://localhost:8080` over kept-alive connections. It signs up, creates a
  project, and builds the NFR-PER-001 workload in production through the management API, as a
  user would; the full answer to a server key is then 506 KiB.
- **Timing:** from the request leaving to the last byte of the body arriving. 500 warm-up pairs of
  a full request and a `304`, then 5,000 full answers one at a time, 5,000 `304`s one at a time, and
  16 clients making 500 full requests each at the same time.
- **Since compression (`8b20628`):** Node's `fetch` asks for gzip and decodes the answer, as the
  SDK does, and the timing runs to the body decoded. The harness also times 16 clients making 500
  `304` requests each at once, and 16 clients making 500 full requests each on a connection of their
  own, read to the last byte as sent and not decoded. Each of the two rulesets ran twice from the host
  and twice from a container on the stack's own Docker network (`node:24-slim`, Node v24.21.0,
  `FLAGLANE_URL=http://api:8080`), interleaved, so that a change of conditions during the session
  would show in both. `RULESET=varied` builds the same shape with random flag keys and match values,
  which compress about 6.5 to one where the generated workload compresses about sixty to one; the two
  bound what a real ruleset sends.
- **Power during those runs:** sampled every five seconds from 18:13:33 to 18:29:10, 187 samples,
  every one on mains. An earlier set of runs at `4d20a36` was discarded: the laptop went onto battery
  at a time between 16:49, when it was last seen on mains, and 17:13, during those runs, and which
  runs that touched cannot be known.

### NFR-PER-003: a change reaching a polling SDK

- **Stack:** the NFR-PER-002 stack, unchanged.
- **Harness:** `npm run bench:propagation` (`sdk/bench/propagation.ts`), Node v24.12.0 on the host,
  unpinned. It builds the same workload in a new project and starts the SDK with its defaults, which
  poll every five seconds.
- **Timing:** 100 changes, each a `PATCH` of the flag under evaluation's rollout in production,
  alternating between 30% and 60% so that each one changes the ruleset. Before each, the harness
  waits a random time of up to five seconds, so changes land at every point of the SDK's poll cycle
  rather than just after a poll. Each is timed from the `PATCH` returning to the SDK holding the
  ruleset version the change produced, checked every millisecond. The server rebuilds its cache
  before the `PATCH` returns, so that version is the one `GET /sdk/config` serves from then on; the
  harness learns it with one more request, which falls inside the time measured.
