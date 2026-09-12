# Roadmap

Status is updated as slices merge. Anything not listed here is not in v0.1.

## Status

| Slice | State |
| --- | --- |
| 1.1 Build skeleton | Done |
| 1.2 Schema | `V1__baseline.sql` done. `V2__roles.sql` and `V3__audit_append_only.sql` are outstanding and follow slice 1.3; see ADR-016 for what V2 now contains |
| 1.3 Docker Compose | Done. The API image carries no HTTP client, so the `api` service has no Compose healthcheck yet |
| Everything else | Not started |

## Principles

- One vertical slice per pull request. A slice is mergeable and leaves `main` releasable.
- Deploy in week 1, before there is anything worth deploying. Deployment is never allowed to be
  the thing that fails in the last week.
- The evaluation engine is the product. It gets the most time and the strictest tests.

## Schedule

Six weeks. The day figures are estimates of solo working days, made after the specification review
and after the cuts below were applied — not a target to be met by working faster.

| Milestone | Days | Weeks |
| --- | --- | --- |
| 1 — Foundation | 9.5 | 1–2 |
| 2 — The engine | 9.0 | 3–4 |
| 3 — Dashboard | 6.0 | 5 |
| 4 — SDK, streaming and hardening | 9.0 | 6 |
| **Total** | **33.5** | **6** |

**Six weeks is 30 working days and the estimate is 33.5.** That gap is stated rather than absorbed
by rounding slices down. It is covered in one of two ways, chosen when it becomes real and not
before:

- the cut list below, or
- the four slices marked ◇ moving to v0.2. They are chosen so that nothing on the *never cut*
  list is at risk: `4.7` rate limiting, `4.8` multi-instance propagation, `3.9` database-level
  audit enforcement, `4.10` the SSE propagation benchmark.

A four-week version of this plan existed and was not true. Milestone 1 alone — ten slices
including JWT authentication and a public deployment with managed PostgreSQL — is two weeks, and
milestone 2 was budgeted at one week for work that does not fit in one week even after cuts.

---

## Milestone 1 — Foundation

Goal: an empty but deployed, tested, documented service.

| Slice | Contents | Days | Requirements |
| --- | --- | --- | --- |
| 1.1 | Gradle, Spring Boot 3, Java 25, package-by-feature, Spotless, static analysis | 1 | — |
| 1.2 | `V1__baseline.sql`, `V2__roles.sql`, `V3__audit_append_only.sql` per `docs/DATABASE.md` | 1 | FR-FLG-001, FR-PRJ-002, FR-AUD-002 |
| 1.3 | Docker Compose: API and PostgreSQL, with the migrator and application roles separated | 0.5 | — |
| 1.4 | GitHub Actions: format, analyse, test, coverage gate, image build | 1 | — |
| 1.5 | springdoc-openapi; Swagger UI live | 0.5 | NFR-MNT-002 |
| 1.6 | Registration, login, JWT filter chain. One access token, no refresh (ADR-012) | 1 | FR-ACC-001, FR-ACC-002 |
| 1.7 | Projects and environments, with `TenantContext` and scoped repositories | 1.5 | FR-PRJ-001 to FR-ENV-004 |
| 1.8 | API key issuance, format, hashing, key cache, authentication filter, revocation | 1.5 | FR-KEY-001 to FR-KEY-007 |
| 1.9 | Flags and per-environment configurations, archive and restore | 1 | FR-FLG-001 to FR-FLG-007 |
| 1.10 | Deployed to a public URL with a managed PostgreSQL | 0.5 | — |

Slice 1.1 carries an open decision: **the static analysis tool is not chosen.** SpotBugs, Error
Prone and PMD are three different dependencies with three different failure modes, and CLAUDE.md
requires asking before adding one. Pick one before 1.1 starts and record it as an ADR; until then
the CI gate in `docs/TESTING.md` names a tool that does not exist.

Exit criteria: a signed-in user creates a project, a flag and a key. CI is green. The service is
reachable on the internet. No evaluation exists yet.

---

## Milestone 2 — The engine

Goal: correct, fast, proven evaluation. The most important milestone; give it the most days.

| Slice | Contents | Days | Requirements |
| --- | --- | --- | --- |
| 2.1 | `evaluation/` package: `Ruleset`, `UserContext`, `Value`. Pure Java, no Spring | 0.5 | NFR-MNT-001 |
| 2.2 | Bucketing and the committed key fixture, with suites 1, 2, 3 and 11 **written first** | 1.5 | FR-EVL-002 to FR-EVL-004, FR-EVL-008 |
| 2.3 | Resolution order, table-driven across every branch, including off vs fallthrough | 1 | FR-EVL-001, FR-EVL-005 |
| 2.4 | Operators and comparison semantics; suite 4a. No regex operator | 1 | FR-RUL-003, FR-RUL-006 to FR-RUL-010 |
| 2.5 | Never-throws behaviour under malformed input | 0.5 | FR-EVL-006, FR-EVL-007 |
| 2.6 | Targeting rules and overrides: persistence, atomic list replacement | 1 | FR-RUL-001 to FR-RUL-004 |
| 2.7 | Ruleset cache: build on startup, rebuild on write, atomic swap, `ruleset_version`, ETag | 1 | ADR-008, NFR-PER-004, FR-SRV-001 |
| 2.8 | `GET /sdk/config` and `POST /sdk/evaluate`, with cache headers. No rate limiting yet | 1 | FR-SRV-001, FR-SRV-002 |
| 2.9 | Tenant isolation suite and client key exposure suite | 1 | NFR-SEC-002, NFR-SEC-004, FR-KEY-008 |
| 2.10 | Audit entries written in the same transaction as the change | 0.5 | FR-AUD-001 |

Rate limiting has moved to 4.7. It is hardening rather than correctness, its algorithm and limits
were undecided when it was scheduled here, and it belongs next to the stream connection limit it
interacts with. Database-level audit enforcement and suite 10 have moved to 3.9, next to the audit
view; what stays here is the non-negotiable, which is that the entry is written in the same
transaction.

Benchmarks have moved to 4.10, where the SSE propagation figure NFR-PER-003 asks for can actually
be measured. NFR-PER-001 and NFR-PER-002 are measured as part of 2.7 and 2.8 and recorded in
`docs/BENCHMARKS.md` as they land.

Exit criteria: suites 1, 2, 3, 4, 4a, 5, 6, 7 and 11 in `docs/TESTING.md` pass. If the cut list has
been used, the exit criteria drop the suites that test what was cut, and the cut is recorded
here. A milestone cannot both cut its content and keep its exit criteria.

---

## Milestone 3 — Dashboard

Goal: the rules are editable by a human.

| Slice | Contents | Days | Requirements |
| --- | --- | --- | --- |
| 3.1 | Vite, TypeScript, router, auth context, generated API client | 0.5 | — |
| 3.2 | Sign in and sign out | 0.5 | FR-UI-001 |
| 3.3 | Project list, creation, environment switcher | 0.5 | FR-UI-002 |
| 3.4 | Flag list per environment | 0.5 | FR-UI-003 |
| 3.5 | Flag detail: kill switch, fallthrough value, rollout slider, explicit save | 1 | FR-UI-004, FR-UI-007 |
| 3.6 | Rule editor with reordering; override list | 1 | FR-UI-004 |
| 3.7 | Key management, with a copy-once display | 0.5 | FR-UI-005 |
| 3.8 | Audit trail view, keyset paginated | 0.5 | FR-UI-006 |
| 3.9 ◇ | Database-level audit append-only enforcement; suite 10 | 1 | FR-AUD-002 |

The rollout slider shows whole percentages and sends whole percentages (ADR-011). It is disabled,
with a reason, when `fallthroughValue` is `true`, because the rollout is inert in that case
(ADR-009).

Exit criteria: a flag is created, targeted and rolled out entirely through the interface, and
every change appears in the audit trail.

---

## Milestone 4 — SDK, streaming and hardening

Goal: a real application consumes it, and Flaglane going down does not matter.

| Slice | Contents | Days | Requirements |
| --- | --- | --- | --- |
| 4.1 | SSE endpoint with `SseEmitter`, connection registry, heartbeat, connection ceiling, revocation closes streams | 1.5 | FR-SRV-003, FR-STR-001 to FR-STR-004 |
| 4.2 | TypeScript SDK: `init`, ruleset cache, in-process evaluation | 1 | FR-SDK-001, FR-SDK-002 |
| 4.3 | Stream subscription with atomic ruleset swap | 0.5 | FR-SDK-003 |
| 4.4 | Reconnect with backoff and jitter; polling fallback | 0.5 | FR-SDK-004 |
| 4.5 | Offline behaviour and never-throws; suite 8 | 0.5 | FR-SDK-005, FR-SDK-006 |
| 4.6 | Parity suite against shared fixtures; suite 9 | 1 | FR-SDK-007 |
| 4.7 ◇ | Rate limiting per key, streams counted at connection | 1 | NFR-SEC-005 |
| 4.8 ◇ | `LISTEN`/`NOTIFY` cache invalidation and stream fan-out across instances | 1 | NFR-PER-003, NFR-REL-002 |
| 4.9 | `examples/demo-shop`: a small storefront using the SDK | 1 | — |
| 4.10 ◇ | SSE propagation benchmark; `docs/BENCHMARKS.md` completed | 0.5 | NFR-PER-003 |
| 4.11 | Release: v0.1.0 tag, CHANGELOG updated | 0.5 | — |

Slice 4.8 is what lifts the single-instance constraint recorded in ADR-013. Until it lands, a
second instance runs a stale cache and its connected SDKs never learn anything changed, so v0.1
ships as a single instance and says so in the README, in `docs/ARCHITECTURE.md` and in the ADR.

Exit criteria: moving the rollout slider in the dashboard visibly changes the demo shop in under
a second. Stopping the API leaves the demo shop working.

---

## Cut list

Under time pressure, cut in this order. Each cut carries a consequence for the milestone's exit
criteria, and the consequence is part of the cut.

1. **SSE (4.1, 4.3, 4.4) — fall back to 5-second polling**, not 30. ADR-004 rejects polling alone
   on the grounds that a 30-second worst case is unacceptable for a kill switch, and cutting to
   exactly that would make the first cut contradict a decision. Five seconds is an accepted
   degradation, recorded in ADR-004 as an amendment. NFR-PER-003's sub-second target does not hold
   under this cut and the README's "live stream" claim becomes "polled every five seconds".
2. **Audit trail view (3.8)** — keep the API and the data. FR-UI-006 drops from milestone 3's exit
   criteria.
3. **Targeting rules (2.4, 2.6, 3.6)** — keep overrides and percentage rollout. Suites 4a and the
   rule rows of suite 4 drop with them, and milestone 2's exit criteria drop those suites
   explicitly rather than being read as still requiring them.

Never cut: the SDK, the demo application, the bucketing suites, deployment, or the benchmark
numbers. Those are the claims the project rests on.

## Beyond v0.1

Not commitments; a record of what was considered and deferred.

- Multivariate flags (string, number, JSON variations)
- A Java SDK
- An OpenFeature provider, so applications already using the standard can point at Flaglane
- Flag lifecycle reporting: last-evaluated timestamps, stale flag detection
- RBAC and audit export
- Persisted, revocable refresh tokens, so sign-out invalidates server-side (ADR-012)
- Configurable `offValue`, once there is a use for it that is not a foot-gun (ADR-009)
- Sub-percent rollouts exposed in the dashboard; the bucketing already supports them (ADR-011)
