# Testing strategy

Tests exist to make claims defensible. Every claim in `README.md` and `docs/SRS.md` about
determinism, distribution, isolation or failure behaviour has a test that would fail if it stopped
being true.

## Layers

| Layer | Tool | Rule |
| --- | --- | --- |
| `evaluation/` | JUnit 5, plain objects | No Spring, no database, 90% line coverage, CI-enforced |
| Services | JUnit 5 + Mockito | Repositories mocked, business rules asserted |
| Repositories | Testcontainers + real PostgreSQL | Never H2. Constraints are part of the design and H2 enforces them differently |
| Controllers | `@WebMvcTest` | Status codes, validation, auth, serialization |
| End to end | Testcontainers, full context | Sign in, create a flag, roll out, evaluate |
| SDK | Vitest | Shared fixtures, offline behaviour, reconnection |
| Dashboard | Vitest, `react-dom/server` | Behaviour in plain modules tested directly; components rendered to static markup in Node (ADR-032) |

## Required suites

These are not optional coverage. Each one substantiates a specific claim.

All bucketing suites run against **one committed key set**:
`backend/src/test/resources/fixtures/user-keys.txt`, 100,000 keys, generated once and checked in.
It includes non-ASCII keys and keys containing `:`, because both are where a hash specified only as
"murmur3" diverges between implementations. Generating keys per run would make the suites sample a
distribution rather than assert a fact, and at ±0.5% on 100,000 keys that is roughly 3σ —
occasional red builds with no bug behind them, in the suites whose whole subject is determinism.

### 1. Bucketing distribution — FR-EVL-002
The committed key set against a 3000 basis-point rollout. Assert the proportion falls between
29.5% and 30.5%. Repeat at 100, 5000 and 9900 basis points. Because the key set is fixed the
computation is fully deterministic, so the band is a safety margin, not a confidence interval.
Substantiates: a percentage rollout means what it says. Implemented in
`BucketingDistributionTest`.

### 2. Bucketing determinism — FR-EVL-003
Run the same 100,000 assignments twice in independent instances. Assert the results are
identical, element by element. Substantiates: a user never sees the interface flicker. Implemented in
`BucketingDeterminismTest`, whose second run loads the bucketing code through its own class loader;
`BucketingTest` also checks every bucket of the key set against the reference C implementation.

### 3. Cross-flag independence — FR-EVL-004
Two flags with different salts, both at 3000 basis points, over the committed key set. **Overlap is
defined as the fraction of all keys receiving both flags** — near 9%, tolerance ±0.5 percentage
points. It is not the Jaccard ratio, which would be near 17.6% for the identical result; stating
which one is meant is the difference between a passing suite and someone "fixing" it. Also assert
two flags sharing a salt select the *same* cohort, since that is what the salt is for.
**This test fails if someone removes the salt from the hash input as a "simplification".**
Implemented in `CrossFlagIndependenceTest`.

### 4. Resolution order — FR-EVL-001
A table-driven suite covering every ordering: kill switch beating an override, override beating a
rule, rule beating rollout, rollout beating fallthrough, and a user at 0% rollout still receiving
the flag via an override. One row per branch, no exceptions. Includes the case that motivated
ADR-009: a disabled configuration whose `fallthroughValue` is `true` still returns `false`.
Implemented in `ResolutionOrderTest`.

### 4a. Comparison semantics — FR-RUL-006 to FR-RUL-009
One row per operator per case: attribute absent, attribute present with the wrong type, case
differing only in capitalisation, `1` against `"1"`, and a `NOT_EQUALS` against an absent
attribute. Run identically in Java and TypeScript from the shared fixtures. Substantiates: the two
implementations agree where the two languages would naturally disagree. The rows are in
`backend/src/test/resources/fixtures/comparison-semantics.json`; `ComparisonSemanticsTest` runs
them rule by rule and through the whole engine. The dashboard's rule editor is held to the same rows
in `dashboard/test/rules.test.ts`: for every row the form can express, it accepts exactly what
`RuleValidator` accepts on write (ADR-034).

### 5. Tenant isolation — NFR-SEC-004
For every tenant-scoped endpoint, authenticate as project A and attempt to read and mutate
project B. Assert 404 in all cases.

The endpoint list is **enumerated at runtime** from Spring's `RequestMappingHandlerMapping`, not
maintained by hand. Every `/api/**` mapping must appear either in the tenant-scoped set or in an
explicit, reviewed exclusion list, and anything unclassified fails the build. A parameterised test
over a hand-written list proves things only about the endpoints someone remembered to add, which
is exactly the endpoint this suite exists to catch. Implemented in `TenantIsolationTest`, which also
calls each endpoint inside the caller's own project and environment naming another tenant's
environment, flag or key.

### 6. Client key exposure — FR-KEY-005
An environment holding both visible and non-visible flags. Assert a client key's `/sdk/config`
contains only the visible ones, and that no rule, override or value from a hidden flag appears
anywhere in the payload. Assert that **no user key from any override, on any flag, visible or
not, appears anywhere in a client key's payload** — search the serialised response for each
override user key in the fixture, not just the parsed `overrides` field, so a future field that
carries one is caught too (FR-KEY-008). Assert that a client key's ETag does not validate a server
key's request:
issue both keys, take the client key's ETag, send it as `If-None-Match` on a server-key request,
and assert 200 with the full body rather than 304. Assert the response carries
`Cache-Control: private, no-store` and `Vary: Authorization`. Implemented in `ClientKeyExposureTest`, which searches the
serialised body for each user key both as written and as JSON escapes it, and also checks that
`POST /sdk/evaluate` with a client key neither applies an override nor reveals a hidden flag.

### 7. Evaluation never throws — FR-EVL-006
Feed the engine a malformed rule, an unknown operator, a null user key, an unknown attribute, an
attribute of an unsupported type and a null ruleset. Assert every call returns `fallthroughValue`
— or the caller's fallback where no configuration is reachable — and that none throws.
Implemented in `NeverThrowsTest`, with the rate limiting of the warnings in
`EvaluationWarningsTest`.

### 8. SDK offline behaviour — FR-SDK-005
Initialise the SDK, load a ruleset, then make the server unreachable. Assert evaluation continues
on the last ruleset. Restart with the server still unreachable and assert the caller's fallback is
returned, without an exception and without blocking beyond the init timeout. Implemented in
`sdk/test/offline.test.ts`, against a real local HTTP server that is stopped, hung, failing or
serving something that is not a ruleset.

### 9. Server and SDK parity — FR-SDK-007
Both implementations evaluate the same fixture set in `backend/src/test/resources/fixtures/` and
must agree on every case. Two implementations of one specification will drift; this is the test
that catches it. Implemented in `ParityFixturesTest` and
`sdk/test/parity.test.ts`, over `evaluation-parity.json`, `comparison-semantics.json` and
`bucketing-vectors.json`; `ParityFixtureShapeTest` keeps the fixture rulesets in the shape the
server actually serialises.

### 10. Audit immutability — FR-AUD-002
Attempt `UPDATE`, `DELETE` and `TRUNCATE` against `audit_entries`, both as the application role and
as the owning role. Assert all fail at the database, not in service code. The fixture provisions
`flaglane_app` as a non-superuser role with the production grants; a test run as
`PostgreSQLContainer`'s default superuser bypasses privilege checks entirely and would pass while
proving nothing, which is why the trigger, not the revoke, is the thing under test. Implemented
in `AuditAppendOnlyTest`; the grants themselves are asserted separately in
`ApplicationRolePrivilegesTest`.

### 11. Rollout monotonicity — FR-EVL-008
Step a flag from 0 to 10000 basis points in 100 steps over the committed key set. Assert that at
every step the set of keys receiving the flag is a superset of the previous step's. Substantiates:
raising a rollout never takes the feature away from someone who already had it, which is the
property that makes the rollout slider safe to move. **This test fails if `bucket <` becomes
`bucket <=` against a re-derived bucket, or if the salt is changed on write.** Implemented in
`RolloutMonotonicityTest`.

### 12. Streams — FR-STR-001 to FR-STR-004
Open real connections to `GET /sdk/stream` and read them as the server writes. Assert a stream's
first event is the version `GET /sdk/config` serves; that a change made through the management API
reaches the streams of its environment, client keys' included, and not another environment's; that
an event names the environment and version and nothing about any flag; that a quiet stream gets a
heartbeat; that revoking a key ends its streams and refuses new ones; that the ceilings per key and
per environment answer 429, and a closed stream gives its place back; and that a stream the server
ends at its lifetime ends without an error in the log, which is how the dispatch ending a stream
was found refused as anonymous (ADR-039). Implemented in `StreamEndToEndTest`, with heartbeats
every second and streams lasting six; the registry's bookkeeping is `StreamRegistryTest`'s.

The SDK's half runs against a real local server that streams: `sdk/test/stream.test.ts` asserts that
an announced change is downloaded at once and not at the next poll, that nothing is polled while the
stream is open and the ruleset current, that a stream the server ends is polled through and
reopened, that a server with no stream or with a JSON answer is polled as before, that a refused
stream waits out its `Retry-After`, that a silent one is dropped, that `close()` ends it, and that no
event it cannot read throws or replaces the ruleset. `sdk/test/stream-parser.test.ts` holds the
parser to the event-stream format, pieces split anywhere and every line ending included.

### 13. Instances — NFR-REL-002, FR-KEY-003 across instances
Run two whole instances of the application against one database, with instance sync on, as two
replicas behind one address. Assert that each reports `instanceSync` `UP`; that a key issued through
one is accepted by the other within a second; that a change through one is served by the other, and
reaches a stream open on the other, within a second; that a key revoked through one is refused by
the other and its stream there ends; and that an instance whose listening connection is cut from the
database's side, while a change commits, catches up on that change when it reconnects. Implemented in
`InstancesEndToEndTest`; the announcement format is `ChangeTest`'s, and what an instance does with an
announcement `ChangeListenerTest`'s. The rest of the suite runs with instance sync off, so its many
application contexts do not rebuild for one another's writes.

## Performance checks

Not pass/fail gates, but recorded and committed, because a claim without a measurement is
marketing.

- In-process evaluation with 1,000 flags and 50 rules on the flag under evaluation: report p50,
  p95, p99 (NFR-PER-001).
- `GET /sdk/config` served from cache: report p99 (NFR-PER-002).
- A change reaching an SDK, from the change being sent to the SDK holding it, over its stream and
  by polling: report p50, p95 and p99 (NFR-PER-003; under a second over a stream, five seconds by
  polling, E-047).

Results live in `docs/BENCHMARKS.md` with the hardware and method stated. Numbers without a method
are not evidence.

## CI gates

`.github/workflows/ci.yml` fails on any of:

1. Formatting differences (`spotlessCheck`)
2. Static analysis warnings (SpotBugs, ADR-015)
3. Any failing test
4. `evaluation/` line coverage below 90%
5. SDK test failures or type errors
6. A Docker image that does not build
7. The demo shop missing a change for longer than the poll interval, or failing with the API stopped
8. Dashboard test failures or type errors, or API types that differ from what the OpenAPI snapshot
   generates

All eight gates are live. Gate 4 is `jacocoTestCoverageVerification`, which `check` runs: it
fails below 90% line coverage in `evaluation/` and nowhere else, and it was confirmed to fail when
raised above the actual figure. Gate 5 is the workflow's SDK job: `npm ci`, then `tsc` with no
emit, then Vitest, then the build, on Node 24. Gate 7 is the workflow's demo job, the exit
criteria of milestones 4 and 5 as a check: it starts the Compose stack with credentials generated
for the run, seeds `examples/demo-shop`, and runs its `verify` script three times (ADR-028). First
the shop runs with `FLAGLANE_STREAMING=false`: four changes through the management API must each
reach it by polling within the five-second interval plus one second, with no stream open, and
raising the rollout must take the feature from nobody. Then the shop runs as shipped: its SDK must
hold a stream, and the same four changes must each reach it within one second. Last, with the API
stopped, the streaming shop must still apply the override, the rule and the rollout.
Gate 8 is the workflow's dashboard job: `npm ci`, then the API types regenerated from
`dashboard/openapi.json` and diffed against the committed ones, then `tsc`, Vitest and the build.
The snapshot itself is held to the served document by `OpenApiSnapshotTest` in gate 3 (ADR-031).

Tests need Docker for Testcontainers. The `ubuntu-latest` runner ships with Docker Engine
running and the runner user in the `docker` group, so Testcontainers finds the daemon at its
default socket; the workflow has no service container and no Docker setup step.

A red build is never merged and never "fixed later".

## Conventions

- Test names read as sentences: `killSwitchOverridesUserOverride()`.
- Arrange, act, assert, with blank lines between. No assertions inside loops without a message.
- Every bug fix opens with a failing test that reproduces it. No exceptions, including for
  one-line fixes.
- Time is injected. A test that sleeps is a test that will be flaky on CI.
