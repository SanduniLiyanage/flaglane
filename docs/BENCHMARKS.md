# Benchmarks

**Nothing has been measured yet.** This file exists so that the links to it from
`docs/TESTING.md` and `docs/ROADMAP.md` resolve, and so that the method is agreed before any
number is recorded. It is filled in as slices 2.7, 2.8 and 4.10 land.

A number without a method is marketing. Every row below records how it was produced, on what
hardware, and against which commit, or it does not go in.

## What is measured

| Requirement | Measurement | Target | Slice |
| --- | --- | --- | --- |
| NFR-PER-001 | In-process evaluation, 1,000 flags loaded, 50 rules on the flag under evaluation | p99 under 25 µs | 2.7 |
| NFR-PER-002 | `GET /sdk/config` served from cache | p99 under 50 ms | 2.8 |
| NFR-PER-003 | Dashboard change to SSE delivery at a connected SDK | p95 under 1 s | 4.10 |

NFR-PER-001's budget was tightened from 1 ms during the specification review (erratum E-013). A
hash and a map lookup cannot approach a millisecond, so the original target was satisfied by
construction and measuring it proved nothing.

## Method

To be written with the first measurement. It must state, at minimum:

- Hardware: CPU model, core count, memory, and whether the machine was otherwise idle.
- Runtime: JDK build and version, Node version for the SDK figures, container or bare metal.
- Commit: the exact SHA measured.
- Harness: how the timing was taken, how many iterations, how warm-up was handled, and which
  percentiles were computed over how many samples.
- Data: the flag and rule counts, and which fixture set supplied the user keys.

Latency figures taken without JVM warm-up are not comparable with figures taken after it, and a
p99 over a few hundred samples is noise. Both are worth saying explicitly, because both are the
usual way benchmark tables end up meaning nothing.

## Results

| Date | Commit | Measurement | p50 | p95 | p99 | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| — | — | — | — | — | — | Not yet measured |
