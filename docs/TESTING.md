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

### 1. Bucketing distribution — FR-EVL-002
100,000 synthetic user keys against a 30% rollout. Assert the proportion falls between 29.5% and
30.5%. Repeat at 1%, 50% and 99%. Substantiates: a percentage rollout means what it says.

### 2. Bucketing determinism — FR-EVL-003
Run the same 100,000 assignments twice in independent instances. Assert the results are
identical, element by element. Substantiates: a user never sees the interface flicker.

### 3. Cross-flag independence — FR-EVL-004
Two different flag keys, both at 30%, over the same 100,000 users. Assert the overlap is near 9%,
not near 30%. Substantiates: the flag key genuinely participates in the hash, and the same cohort
is not the test population for every rollout. **This test fails if someone removes the flag key
from the hash input as a "simplification".**

### 4. Resolution order — FR-EVL-001
A table-driven suite covering every ordering: kill switch beating an override, override beating a
rule, rule beating rollout, rollout beating default, and a user at 0% rollout still receiving the
flag via an override. One row per branch, no exceptions.

### 5. Tenant isolation — NFR-SEC-004
For every tenant-scoped endpoint, authenticate as project A and attempt to read and mutate
project B. Assert 404 in all cases. Written as a parameterised test over the endpoint list, so a
new endpoint added without isolation fails the build.

### 6. Client key exposure — FR-KEY-005
An environment holding both visible and non-visible flags. Assert a client key's `/sdk/config`
contains only the visible ones, and that no rule, override or default from a hidden flag appears
anywhere in the payload.

### 7. Evaluation never throws — FR-EVL-006
Feed the engine a malformed rule, an unparseable regex, a null user key, an unknown attribute and
a null ruleset. Assert every call returns the default and none throws.

### 8. SDK offline behaviour — FR-SDK-005
Initialise the SDK, load a ruleset, then make the server unreachable. Assert evaluation continues
on the last ruleset. Restart with the server still unreachable and assert the caller's fallback is
returned, without an exception and without blocking beyond the init timeout.

### 9. Server and SDK parity — FR-SDK-007
Both implementations evaluate the same fixture set in `backend/src/test/resources/fixtures/` and
must agree on every case. Two implementations of one specification will drift; this is the test
that catches it.

### 10. Audit immutability — FR-AUD-002
Attempt update and delete against `audit_entries` as the application role. Assert both fail at the
database, not in service code.

## Performance checks

Not pass/fail gates, but recorded and committed, because a claim without a measurement is
marketing.

- In-process evaluation with 1,000 flags: report p50, p95, p99 (NFR-PER-001).
- `GET /sdk/config` served from cache: report p99 (NFR-PER-002).
- Dashboard change to SSE delivery: report p95 (NFR-PER-003).

Results live in `docs/BENCHMARKS.md` with the hardware and method stated. Numbers without a method
are not evidence.

## CI gates

`.github/workflows/ci.yml` fails on any of:

1. Formatting differences (`spotlessCheck`)
2. Static analysis warnings
3. Any failing test
4. `evaluation/` line coverage below 90%
5. SDK test failures or type errors
6. A Docker image that does not build

A red build is never merged and never "fixed later".

## Conventions

- Test names read as sentences: `killSwitchOverridesUserOverride()`.
- Arrange, act, assert, with blank lines between. No assertions inside loops without a message.
- Every bug fix opens with a failing test that reproduces it. No exceptions, including for
  one-line fixes.
- Time is injected. A test that sleeps is a test that will be flaky on CI.
