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
Substantiates: a percentage rollout means what it says.

### 2. Bucketing determinism — FR-EVL-003
Run the same 100,000 assignments twice in independent instances. Assert the results are
identical, element by element. Substantiates: a user never sees the interface flicker.

### 3. Cross-flag independence — FR-EVL-004
Two flags with different salts, both at 3000 basis points, over the committed key set. **Overlap is
defined as the fraction of all keys receiving both flags** — near 9%, tolerance ±0.5 percentage
points. It is not the Jaccard ratio, which would be near 17.6% for the identical result; stating
which one is meant is the difference between a passing suite and someone "fixing" it. Also assert
two flags sharing a salt select the *same* cohort, since that is what the salt is for.
**This test fails if someone removes the salt from the hash input as a "simplification".**

### 4. Resolution order — FR-EVL-001
A table-driven suite covering every ordering: kill switch beating an override, override beating a
rule, rule beating rollout, rollout beating fallthrough, and a user at 0% rollout still receiving
the flag via an override. One row per branch, no exceptions. Includes the case that motivated
ADR-009: a disabled configuration whose `fallthroughValue` is `true` still returns `false`.

### 4a. Comparison semantics — FR-RUL-006 to FR-RUL-009
One row per operator per case: attribute absent, attribute present with the wrong type, case
differing only in capitalisation, `1` against `"1"`, and a `NOT_EQUALS` against an absent
attribute. Run identically in Java and TypeScript from the shared fixtures. Substantiates: the two
implementations agree where the two languages would naturally disagree.

### 5. Tenant isolation — NFR-SEC-004
For every tenant-scoped endpoint, authenticate as project A and attempt to read and mutate
project B. Assert 404 in all cases.

The endpoint list is **enumerated at runtime** from Spring's `RequestMappingHandlerMapping`, not
maintained by hand. Every `/api/**` mapping must appear either in the tenant-scoped set or in an
explicit, reviewed exclusion list, and anything unclassified fails the build. A parameterised test
over a hand-written list proves things only about the endpoints someone remembered to add, which
is exactly the endpoint this suite exists to catch.

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
`Cache-Control: private, no-store` and `Vary: Authorization`.

### 7. Evaluation never throws — FR-EVL-006
Feed the engine a malformed rule, an unknown operator, a null user key, an unknown attribute, an
attribute of an unsupported type and a null ruleset. Assert every call returns `fallthroughValue`
— or the caller's fallback where no configuration is reachable — and that none throws.

### 8. SDK offline behaviour — FR-SDK-005
Initialise the SDK, load a ruleset, then make the server unreachable. Assert evaluation continues
on the last ruleset. Restart with the server still unreachable and assert the caller's fallback is
returned, without an exception and without blocking beyond the init timeout.

### 9. Server and SDK parity — FR-SDK-007
Both implementations evaluate the same fixture set in `backend/src/test/resources/fixtures/` and
must agree on every case. Two implementations of one specification will drift; this is the test
that catches it.

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
`bucket <=` against a re-derived bucket, or if the salt is changed on write.**

## Performance checks

Not pass/fail gates, but recorded and committed, because a claim without a measurement is
marketing.

- In-process evaluation with 1,000 flags and 50 rules on the flag under evaluation: report p50,
  p95, p99 (NFR-PER-001).
- `GET /sdk/config` served from cache: report p99 (NFR-PER-002).
- Dashboard change to SSE delivery: report p95 (NFR-PER-003).

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

Gates 1, 2, 3 and 6 are live. Gate 4 is wired when the `evaluation/` package lands in slice
2.1, and gate 5 when the SDK lands in slice 4.2; until then the workflow says so in its header
comment rather than pretending to enforce a package that does not exist.

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
