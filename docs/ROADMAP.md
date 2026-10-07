# Roadmap

Status is updated as slices merge. Anything not listed here is not in v0.1.

## Status

| Slice | State |
| --- | --- |
| 1.1 Build skeleton | Done |
| 1.2 Schema | Done. `V2__roles.sql` grants; the role itself is provisioned by the environment (ADR-016). `V3__audit_append_only.sql` landed with it, together with suite 10, because a trigger without its suite is not done — which completes slice 3.9 early |
| 1.3 Docker Compose | Done. The API image carries no HTTP client, so the `api` service has no Compose healthcheck yet |
| 1.4 GitHub Actions | Done. Format, analysis, tests, image build, and since slice 2.5 the coverage gate: `check` fails below 90% line coverage in `evaluation/` (97.9% when it landed) |
| 1.5 springdoc-openapi | Done. `/v3/api-docs` and Swagger UI are served; the document has no paths until slice 1.6 adds the first endpoint |
| 1.6 Registration, login, JWT | Done. `POST /api/auth/register` and `/login`; one 8-hour HS256 token, verified by Spring Security's resource server (ADR-021). `/api/**` and everything else have separate filter chains. Registration is not audited (E-037). `FLAGLANE_JWT_SECRET` is required by Compose and has no default |
| 1.7 Projects and environments | Done. Tenancy is structural: repositories take `OwnerScope`, `ProjectScope` or `EnvironmentScope`, which only `TenantResolver` creates by an owner-scoped query, and an interceptor resolves them before the request body is read. Suite 5 passes, with endpoints enumerated from `RequestMappingHandlerMapping`. Every mutation is audited in its own transaction (`Propagation.MANDATORY`). Environment deletion needed V4 (ADR-022). FR-ENV-004 is published as an event here and lands with flags in 1.9 |
| 1.8 API keys | Done. `flg_srv_`/`flg_cli_` keys from 32 CSPRNG bytes; only the SHA-256 and a 16-character prefix are stored, and the key is in one response. `/sdk/**` has its own chain and authenticates from an in-memory key cache with no database query, updated after commit so a revoked key is refused before the revoke returns. `last_used_at` is flushed once a minute off the request path. Every `/sdk/**` response, 401s included, is `Cache-Control: private, no-store` with `Vary: Authorization`. Suite 5 now also tries other tenants' key ids inside the caller's own environment |
| 1.9 Flags and configurations | Done. A flag gets a disabled, 0% configuration in every environment; a new environment gets one for every live flag (FR-ENV-004); restoring a flag fills in environments created while it was archived. Archived flags are read-only until restored. The management API takes and shows whole percentages and stores basis points; fractional percentages are refused rather than truncated. `ruleset_version` is bumped in the transaction of every write that changes what an environment serves, and a `RulesetChanged` event is published for the cache in 2.7 |
| 2.1 `evaluation/` types | Done. `Ruleset`, `FlagConfig`, `TargetingRule`, `UserContext` and the `Value` family; a source-level test fails the build if `evaluation/` imports anything outside `java.*`. Number equality and the empty user key decided in ADR-017 |
| 2.2 Bucketing | Done. MurmurHash3 x86_32 in `evaluation/`, buckets 0–9999. Suites 1, 2, 3 and 11 were written first and pass against the committed 100,000-key fixture; every bucket of that fixture matches the reference C implementation. Unpaired surrogates hash as U+FFFD (ADR-018) |
| 2.3 Resolution order | Done. `Evaluator` returns a value and a `Reason`; suite 4 is one row per branch, 26 rows, and suite 4a's fixture runs through the whole engine as well as rule by rule. A malformed rule that evaluation reaches resolves to the fallthrough value (ADR-020) |
| 2.5 Never-throws | Done. Suite 7 passes: malformed rules, unknown operators, null user keys and contexts, unknown and unsupported attributes, and a null ruleset all return rather than throw, as does a simulated internal defect and a logger that fails. Warnings are rate-limited to one per flag key per minute through an injected `Clock`, never include the user key, and are bounded in memory against floods of unknown flag keys |
| 2.6 Targeting rules and overrides | Done. `PUT .../rules` and `.../overrides` replace the whole list in one transaction, deleting in SQL before inserting so no two rules ever share a priority; priorities run from 0 with no gaps, asserted on the rows (FR-RUL-004). A rule is refused on write by the engine's own definition of malformed, via `RuleValidator` in `evaluation/` (FR-RUL-010). Limits per configuration in ADR-023. The tenant interceptor now also resolves `{flagKey}`, so another tenant's flag is a 404 before any body is validated |
| 2.7 Ruleset cache | Done, in a new `serving/` package (ADR-024). Each environment's snapshot is built from four queries in one `REPEATABLE READ` transaction, so its version always matches its content, and holds a server and a client ruleset with their JSON bodies; the client one has visible flags only and no `overrides` field. Built before the application accepts requests, rebuilt after commit, never moved to an older version, reconciled every minute. Readiness now depends on it and on nothing else. NFR-PER-001 and NFR-PER-002 were measured on 2026-10-07 and are in `docs/BENCHMARKS.md` |
| 2.8 `GET /sdk/config`, `POST /sdk/evaluate` | Done. Both answer from the ruleset cache alone; a test stops the database and both keep answering while the management API reports 503. `GET /sdk/config` serves the key type's body with ETag `"<version>-<key type>"` and 304 on a match, and a client key's ETag never validates a server key's request. `POST /sdk/evaluate` runs the engine against the same filtered ruleset, so a client key gets no overrides and a hidden flag is `FLAG_NOT_FOUND` with the caller's fallback, never a 404. 503 with `Retry-After` until the cache has the environment. No rate limiting yet (4.7) |
| 2.9 Isolation and exposure suites | Done. Suite 5 landed with 1.7. Suite 6 is its own suite: a client key's payload is searched as text for every hidden flag's rules, salt and values and every override user key on every flag, raw and JSON-escaped; ETags do not cross key types in either direction; a key serves its own environment only. Leaking overrides into the client ruleset was confirmed to fail it |
| 2.10 Audit in the same transaction | Done early, with slice 1.7's first mutations: `AuditLog.record` is `Propagation.MANDATORY`, so an entry cannot commit apart from its change. Each later slice audits its own mutations as it lands |
| 2.4 Operators | Done, ahead of 2.3, because the resolution-order suite needs rules that can match. Seven operators, compiled once per ruleset build. Suite 4a's rows are in the shared fixture `comparison-semantics.json` for the SDK to run too. Operator shapes and negation decided in ADR-019 |
| 4.1, 4.3, 4.4 Streaming | **Cut for v0.1** — cut list item 1, invoked on 2026-10-06 (ADR-027). No `GET /sdk/stream`; SDKs poll every five seconds, with backoff and jitter while the server is unreachable, which shipped with 4.2 |
| 4.2 TypeScript SDK | Done. `@flaglane/sdk` in `sdk/`: `init`, ruleset cache, in-process evaluation, no runtime dependencies (ADR-026). Updates by polling `GET /sdk/config` every five seconds with its ETag. Ready to publish; not yet published to npm |
| 4.5 Offline and never-throws | Done. Suite 8 runs the SDK against a real local server that is stopped, hung, failing or serving garbage: evaluation carries on from the last ruleset, a start with Flaglane unreachable answers with the caller's fallback, startup is held no longer than the init timeout, and nothing throws |
| 4.6 Parity | Done. Suite 9: the shared fixtures `evaluation-parity.json`, `comparison-semantics.json` and `bucketing-vectors.json` run in both implementations, the TypeScript half written before the SDK's engine and seen failing against stubs; every bucket of the 100,000-key set agrees with the reference MurmurHash3 on both sides |
| 4.9 Demo shop | Done. `examples/demo-shop`: a storefront on the SDK with a server key, a rollout with an override and a targeting rule, and scripts that stand in for the dashboard through the management API (ADR-028). `npm run verify` checks milestone 4's exit criteria against a running stack; against Compose, four changes reached the shop in 0.05 to 4.99 s, and with the API stopped the shop kept applying the override, the rule and the rollout from its last ruleset. CI runs both checks on every push (gate 7). Linked from `sdk/` until the package is published |
| Sign-in rate limit | Done ahead of 1.10, which makes registration and sign-in public (ADR-029, NFR-SEC-006). 10 requests a minute per client address by default, 429 with `Retry-After` before any password is hashed. The token bucket is the one 4.7 will put in front of `/sdk/**`. Behind the host's proxy it needs forwarded headers read, which is part of 1.10 |
| 3.10 Audit read API | Done, ahead of the view that needs it (3.8). Newest first, keyset on `(created_at, id)` compared as a row so PostgreSQL walks the project index from the cursor; narrowed by `environment` or `flag` key; entries name actor, environment and flag rather than giving ids, and a deleted environment keeps its key, read from its deletion entry through a partial index (V5). Recorded states are returned as recorded (ADR-030) |
| 3.1 Dashboard skeleton | Done, as ADR-031 decided it. One origin: Vite proxies `/api` in development, and the image builds `dashboard/` and the API serves it, with only `/`, the page's assets, `/sign-in` and `/projects/**` opened; CORS is unchanged. The built page carries a Content-Security-Policy of its own origin only. Types are generated from `dashboard/openapi.json`, which `OpenApiSnapshotTest` keeps equal to the served document and CI regenerates and diffs. Generating them found half the operations documenting no success response and every response field optional; both were fixed in the document. React and Vite and nothing else at runtime (ADR-032). A router over the History API and a fetch wrapper typed by operation |
| 3.2 Sign in and out | Done. The token lives in one tab's memory; the sign-in page says so, a reload costs a password and returns to the same URL, and signing out says a copied token stays valid until it expires (E-029). The session ends at expiry or on a 401, with the reason shown. Registration is on the same page, so the dashboard is usable from the first visit |
| 3.3 Projects and environment switcher | Done. A project opens on its development environment; the switcher keeps the page, a flag's detail included |
| 3.4 Flag list | Done, with flag creation, which no slice scheduled and milestone 3's exit criterion needs. One request for every configuration of an environment, `GET .../environments/{envKey}/configs`, added for it |
| 3.5 Flag detail | Done. The kill switch is one confirmed action that saves at once and leaves staged edits staged; the fallthrough value and the rollout are staged and saved with one `PATCH` of the changed fields; the rollout is a range of whole percentages, disabled with its reason while the fallthrough value is true. Leaving unsaved changes asks first. Checked end to end in a browser against the built image |
| 4.10 Propagation benchmark | Done, with the figures 2.7 and 2.8 owed. On 2026-10-07, at `f4097c9`, on mains power: evaluation p99 1.0–1.4 µs in the server's engine and 2.4–3.9 µs in the SDK against 25 µs; `GET /sdk/config` p99 27–30 ms for one client against 50 ms, and 79–94 ms with sixteen clients downloading at once, above it and stated; a change reached a polling SDK in at most 4.94 s over 100 changes. Then the ruleset was gzipped once when built (ADR-033) and `GET /sdk/config` measured again at `8b20628`: one client p99 8–10 ms; sixteen at once p99 43–65 ms, still above target in three runs of four, of which the server and network are 8–14 ms and most of the rest is the measuring process decoding sixteen answers together. Method and results in `docs/BENCHMARKS.md` |
| 3.6 Rule editor and overrides | Done. Rules are staged as the whole ordered list and saved in one `PUT` (FR-RUL-004), moved with buttons; every check `RuleValidator` and ADR-023 make on write is made as the rule is edited, and suite 4a's fixture holds the form to the server for every row it can express. A rule on `key`, which the SDK never matches, and an empty text value are warned about rather than refused, since the API accepts both. Overrides are a set, one entry per user key, and the page says they reach server keys only (ADR-034) |
| 3.7 Key management | Done. The plaintext key is shown in the one response that carries it, with a copy control, and leaving while it is shown asks first; afterwards the list shows the prefix only. Revoking says what it does before it is done: refused from the moment it returns, applications already running keep their last ruleset without further changes, one started later gets its code's defaults |
| 3.8 Audit trail view | Done, over 3.10's read API: newest first, fifty at a time by keyset, narrowed to an environment or a flag through the address. Entries name the actor, environment and flag, a deleted environment by the key it had. Recorded states are shown as recorded, a rollout in basis points with the unit named and the whole percentage beside it where it is one (ADR-034). **Milestone 3's exit criterion holds:** a flag created, targeted with an override and two rules, turned on and rolled out to 30% entirely through the interface, checked in a browser against the built image, with each of its changes in the trail and the ruleset its key downloads carrying exactly what the interface set |
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
- the slices marked ◇ moving to v0.2. They are chosen so that nothing on the *never cut* list
  is at risk: `4.7` rate limiting, `4.8` multi-instance propagation, `4.10` the propagation
  benchmark. `3.9` database-level audit enforcement was one of them and has since landed with
  slice 1.2.

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
were undecided when it was scheduled here, and it belonged next to the stream connection limit it
interacted with, a limit the streaming cut has since removed (ADR-027). Database-level audit enforcement and suite 10 have moved to 3.9, next to the audit
view; what stays here is the non-negotiable, which is that the entry is written in the same
transaction.

Benchmarks have moved to 4.10, where the propagation figure NFR-PER-003 asks for can actually be
measured — a polling interval since the streaming cut. NFR-PER-001 and NFR-PER-002 are measured as part of 2.7 and 2.8 and recorded in
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
| 3.9 ◇ | Database-level audit append-only enforcement; suite 10. **Done early**, with `V3__audit_append_only.sql` in slice 1.2, and no longer a candidate for v0.2 | 1 | FR-AUD-002 |
| 3.10 | Audit read API: `GET /api/projects/{projectKey}/audit`, keyset paginated, narrowed by environment or flag. Added on 2026-10-06: FR-AUD-003 and `docs/API.md` specified the endpoint but no slice scheduled it, and 3.8 cannot be built without it | 0.5 | FR-AUD-003 |

The rollout slider shows whole percentages and sends whole percentages (ADR-011). It is disabled,
with a reason, when `fallthroughValue` is `true`, because the rollout is inert in that case
(ADR-009).

Exit criteria: a flag is created, targeted and rolled out entirely through the interface, and
every change appears in the audit trail.

---

## Milestone 4 — SDK, propagation and hardening

Goal: a real application consumes it, and Flaglane going down does not matter.

| Slice | Contents | Days | Requirements |
| --- | --- | --- | --- |
| 4.1 ✂ | ~~SSE endpoint with `SseEmitter`, connection registry, heartbeat, connection ceiling, revocation closes streams~~ Cut (ADR-027) | 1.5 | FR-SRV-003, FR-STR-001 to FR-STR-004 |
| 4.2 | TypeScript SDK: `init`, ruleset cache, in-process evaluation | 1 | FR-SDK-001, FR-SDK-002 |
| 4.3 ✂ | ~~Stream subscription with atomic ruleset swap~~ Cut (ADR-027); the SDK polls and swaps atomically instead | 0.5 | FR-SDK-003 |
| 4.4 ✂ | ~~Reconnect with backoff and jitter; polling fallback~~ Cut (ADR-027): no stream to reconnect. Polling with backoff and jitter shipped in 4.2 | 0.5 | FR-SDK-004 |
| 4.5 | Offline behaviour and never-throws; suite 8 | 0.5 | FR-SDK-005, FR-SDK-006 |
| 4.6 | Parity suite against shared fixtures; suite 9 | 1 | FR-SDK-007 |
| 4.7 ◇ | Rate limiting per key | 1 | NFR-SEC-005 |
| 4.8 ◇ | `LISTEN`/`NOTIFY` cache invalidation across instances | 1 | NFR-PER-003, NFR-REL-002 |
| 4.9 | `examples/demo-shop`: a small storefront using the SDK | 1 | — |
| 4.10 ◇ | Propagation benchmark: a change to SDK pickup under polling; `docs/BENCHMARKS.md` completed | 0.5 | NFR-PER-003 |
| 4.11 | Release: v0.1.0 tag, CHANGELOG updated | 0.5 | — |

Slice 4.8 is what lifts the single-instance constraint recorded in ADR-013. Until it lands, a
second instance runs a stale cache and the SDKs polling it never see a change made on the other,
so v0.1 ships as a single instance and says so in the README, in `docs/ARCHITECTURE.md` and in
the ADR.

Exit criteria: moving the rollout — in the dashboard, or through the management API until the
dashboard exists — visibly changes the demo shop **within five seconds**. Stopping the API leaves the
demo shop working.

**Cut recorded:** cut list item 1 was invoked on 2026-10-06 (ADR-027). Slices 4.1, 4.3 and 4.4 are
not in v0.1, and the exit criterion above is the cut's: five seconds, the poll interval, where it
said under a second. NFR-PER-003's sub-second target drops with it (E-039). No suite tested the
stream, so none drops.

---

## Cut list

Under time pressure, cut in this order. Each cut carries a consequence for the milestone's exit
criteria, and the consequence is part of the cut.

1. **SSE (4.1, 4.3, 4.4) — fall back to 5-second polling**, not 30. **Invoked on 2026-10-06
   (ADR-027)**, and recorded in milestone 4's exit criteria. ADR-004 rejects polling alone
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
