# Roadmap

Status is updated as slices merge. Anything not listed here is not in v0.1.

## Status

| Area | State |
| --- | --- |
| Everything | Not started |

## Principles

- One vertical slice per pull request. A slice is mergeable and leaves `main` releasable.
- Deploy in week 1, before there is anything worth deploying. Deployment is never allowed to be
  the thing that fails in week 4.
- The evaluation engine is the product. It gets the most time and the strictest tests.

---

## Milestone 1 — Foundation

Goal: an empty but deployed, tested, documented service.

| Slice | Contents | Requirements |
| --- | --- | --- |
| 1.1 | Gradle, Spring Boot 3, Java 21, package-by-feature, Spotless, static analysis | — |
| 1.2 | `V1__baseline.sql` with every table, constraint and index in `docs/DATABASE.md` | FR-FLG-001, FR-PRJ-002 |
| 1.3 | Docker Compose: API and PostgreSQL | — |
| 1.4 | GitHub Actions: format, analyse, test, coverage gate, image build | NFR-MNT-002 |
| 1.5 | springdoc-openapi; Swagger UI live | NFR-MNT-002 |
| 1.6 | Registration, login, refresh, JWT filter chain | FR-ACC-001, FR-ACC-002 |
| 1.7 | Projects and environments, with `TenantContext` and scoped repositories | FR-PRJ-001 to FR-ENV-003 |
| 1.8 | API key issuance, hashing, authentication filter, revocation | FR-KEY-001 to FR-KEY-006 |
| 1.9 | Flags and per-environment configurations | FR-FLG-001 to FR-FLG-006 |
| 1.10 | Deployed to a public URL with a managed PostgreSQL | — |

Exit criteria: a signed-in user creates a project, a flag and a key. CI is green. The service is
reachable on the internet. No evaluation exists yet.

---

## Milestone 2 — The engine

Goal: correct, fast, proven evaluation. The most important milestone; give it the most days.

| Slice | Contents | Requirements |
| --- | --- | --- |
| 2.1 | `evaluation/` package: `Ruleset`, `UserContext`, `Value`. Pure Java, no Spring | NFR-MNT-001 |
| 2.2 | Bucketing, with suites 1, 2 and 3 from `docs/TESTING.md` **written first** | FR-EVL-002 to FR-EVL-004 |
| 2.3 | Resolution order, table-driven across every branch | FR-EVL-001, FR-EVL-005 |
| 2.4 | Operators, including regex with length limit and match timeout | FR-RUL-003, FR-RUL-005 |
| 2.5 | Never-throws behaviour under malformed input | FR-EVL-006, FR-EVL-007 |
| 2.6 | Targeting rules and overrides: persistence, atomic list replacement | FR-RUL-001 to FR-RUL-004 |
| 2.7 | Ruleset cache: build on startup, rebuild on write, atomic swap, ETag | ADR-008, NFR-PER-004 |
| 2.8 | `GET /sdk/config` and `POST /sdk/evaluate`, with rate limiting | FR-SRV-001, FR-SRV-002 |
| 2.9 | Tenant isolation suite and client key exposure suite | NFR-SEC-002, NFR-SEC-004 |
| 2.10 | Audit entries in-transaction, append-only enforced at the database | FR-AUD-001 to FR-AUD-003 |
| 2.11 | Benchmarks recorded in `docs/BENCHMARKS.md` | NFR-PER-001, NFR-PER-002 |

Exit criteria: every suite in `docs/TESTING.md` numbered 1 through 7 and 10 passes. Benchmarks are
committed with their method.

---

## Milestone 3 — Dashboard

Goal: the rules are editable by a human.

| Slice | Contents | Requirements |
| --- | --- | --- |
| 3.1 | Vite, TypeScript, router, auth context, generated API client | — |
| 3.2 | Sign in and sign out | FR-UI-001 |
| 3.3 | Project list, creation, environment switcher | FR-UI-002 |
| 3.4 | Flag list per environment | FR-UI-003 |
| 3.5 | Flag detail: kill switch, default, rollout slider, explicit save | FR-UI-004, FR-UI-007 |
| 3.6 | Rule editor with reordering; override list | FR-UI-004 |
| 3.7 | Key management, with a copy-once display | FR-UI-005 |
| 3.8 | Audit trail view | FR-UI-006 |

Exit criteria: a flag is created, targeted and rolled out entirely through the interface, and
every change appears in the audit trail.

---

## Milestone 4 — SDK and streaming

Goal: a real application consumes it, and Flaglane going down does not matter.

| Slice | Contents | Requirements |
| --- | --- | --- |
| 4.1 | SSE endpoint, connection registry, heartbeat, revocation closes streams | FR-SRV-003, FR-STR-001 to FR-STR-003 |
| 4.2 | TypeScript SDK: `init`, ruleset cache, in-process evaluation | FR-SDK-001, FR-SDK-002 |
| 4.3 | Stream subscription with atomic ruleset swap | FR-SDK-003 |
| 4.4 | Reconnect with backoff and jitter; polling fallback | FR-SDK-004 |
| 4.5 | Offline behaviour and never-throws; suite 8 | FR-SDK-005, FR-SDK-006 |
| 4.6 | Parity suite against shared fixtures; suite 9 | FR-SDK-007 |
| 4.7 | `examples/demo-shop`: a small storefront using the SDK | — |
| 4.8 | Release: `docs/BENCHMARKS.md`, CHANGELOG, CONTRIBUTING, SECURITY, v0.1.0 tag | — |

Exit criteria: moving the rollout slider in the dashboard visibly changes the demo shop in under
a second. Stopping the API leaves the demo shop working.

---

## Cut list

Under time pressure, cut in this order:

1. SSE (4.1, 4.3, 4.4) — fall back to 30-second polling
2. Audit trail view (3.8) — keep the API and the data
3. Targeting rules (2.4, 2.6, 3.6) — keep overrides and percentage rollout

Never cut: the SDK, the demo application, the bucketing suites, deployment, or the benchmark
numbers. Those are the claims the project rests on.

## Beyond v0.1

Not commitments; a record of what was considered and deferred.

- Multivariate flags (string, number, JSON variations)
- A Java SDK
- An OpenFeature provider, so applications already using the standard can point at Flaglane
- Flag lifecycle reporting: last-evaluated timestamps, stale flag detection
- RBAC and audit export
