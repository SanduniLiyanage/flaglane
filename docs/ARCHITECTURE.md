# Architecture

## 1. Shape of the system

```
Dashboard (React)  ──JWT──►  Management API  ─┐
                                              ├─►  Service layer  ──►  PostgreSQL
Your application               Serving API  ──┘         │
  └─ Flaglane SDK  ──API key──►     │                   │
         │                          ▼                   ▼
         └── polls every 5 s ──► Ruleset cache ◄── rebuilt after each write
```

Two API surfaces, deliberately separate (see ADR-006):

| | Management API | Serving API |
| --- | --- | --- |
| Path | `/api/**` | `/sdk/**` |
| Caller | Dashboard | SDKs in customer applications |
| Auth | JWT bearer | API key |
| Traffic | Low, human-driven | High, machine-driven |
| Latency budget | Generous | Tight |
| On failure | Show an error | Must degrade silently |

They differ in every dimension that matters, so they get different controllers, different
security filter chains, different rate limits and different error handling.

## 2. Evaluation: in process, not over the network

The naive design has the SDK call the server for every flag check. That puts a network round trip
in the customer's request path and makes Flaglane's availability their availability.

Instead: the SDK downloads the **entire ruleset** for its environment once, evaluates locally in
memory, and re-checks it every five seconds. Consequences:

- Evaluation is a hash and a few comparisons — microseconds, no I/O.
- Flaglane can be down and applications keep working on the last ruleset.
- The rules are visible to the client, so a client key must only receive client-visible flags
  (FR-KEY-005) and no user overrides at all (FR-KEY-008). Filtering by flag is not enough: an
  override's user key is a real user identifier, and shipping the override list tells every
  browser which specific people you have been targeting.
- Changes are not instant. In v0.1 the SDK polls with the ETag of the ruleset it holds, so a change
  arrives within about five seconds and an unchanged ruleset costs an empty 304; the stream that
  would make it sub-second was cut for v0.1 (ADR-027, section 5).

`POST /sdk/evaluate` exists for thin clients that cannot hold a ruleset, and runs the identical
engine server-side.

## 3. The evaluation engine

`io.github.sanduniliyanage.flaglane.evaluation` is plain Java. No Spring, no JPA, no database,
and no import from any other Flaglane package, which a test enforces. It is a pure function:

```
evaluate(Ruleset, flagKey, UserContext, fallback) -> Evaluation(value, reason)
```

The reason is the step of the resolution order that produced the value — `OFF`, `OVERRIDE`,
`RULE_MATCH`, `ROLLOUT`, `FALLTHROUGH`, `FLAG_NOT_FOUND` or `ERROR` — and is what `POST
/sdk/evaluate` reports per flag. The engine produces it rather than the endpoint deriving it, so
there is one implementation of the order, not two. A user attribute or rule operand is a `Value`:
a string, a number (an IEEE-754 double, as in JavaScript) or a boolean, compared type-strictly
(ADR-017, ADR-019).

Purity is a design requirement, not an aesthetic. It makes the engine exhaustively testable
without a container, and it means the same logic can be described precisely enough for the
TypeScript SDK to reimplement identically.

### Resolution order (FR-EVL-001)

1. **Kill switch.** Configuration disabled → `offValue`. Nothing else is consulted.
2. **User override.** Exact match on user key → its value.
3. **Targeting rules.** Priority ascending, first match wins → its result value.
4. **Percentage rollout.** `bucket < rolloutBasisPoints` → `true`.
5. **Fallthrough.** `fallthroughValue`.

Order matters and is not arbitrary. Overrides sit above rules so an engineer can put themselves
in a feature that is at 0%. The kill switch sits above everything so the 2am rollback needs no
reasoning about rules.

`offValue` and `fallthroughValue` are two different things and were one field until ADR-009. The
value a disabled flag returns is fixed `false` and not editable; the value an enabled flag returns
when nothing matched is editable. Collapsing them made the kill switch conditional on a setting,
which is the one control that has to be unconditional.

### Bucketing (FR-EVL-002 to FR-EVL-008)

```
bucket = murmur3_32_x86(utf8(rolloutSalt + ":" + userKey), seed = 0) unsigned % 10000
```

`rolloutSalt` defaults to the flag key at creation (ADR-010), and the modulus is 10000 rather than
100 so a rollout is expressed in basis points (ADR-011). The dashboard still shows and accepts
integer percentages; it multiplies by 100 on the way in.

The variant, the encoding and the unsigned conversion are all specified in FR-EVL-002 rather than
left to each implementation. They look pedantic and they are the exact points at which a Java
implementation and a TypeScript one silently disagree. MurmurHash3 x86_32 is about forty lines and
is implemented inside `evaluation/` rather than pulled in as a dependency; if Guava ever arrives
for another reason, `Hashing.murmur3_32_fixed()` is the correct constructor and the deprecated
`murmur3_32()` is not — it mishandles non-ASCII input. One case has no UTF-8 encoding at all: a key
holding an unpaired UTF-16 surrogate. It is encoded with U+FFFD in its place, which is what
`TextEncoder` does on the SDK's side and not what Java's `String.getBytes` does (ADR-018). Every
bucket of the 100,000-key test fixture is asserted against the reference C implementation.

Four properties matter:

**Deterministic.** The same user must get the same answer on every server, after every restart,
for the life of the flag. If it changed, a user would watch the interface flicker between two
versions. This rules out random numbers, and it rules out anything seeded by process start, host
identity, or wall-clock time.

**Uniform.** A 30% rollout must reach approximately 30% of users. MurmurHash3 distributes evenly
across the 0–9999 range for realistic user key distributions, and this is asserted by test, not
assumed.

**Independent per flag.** The salt is part of the hash input and defaults to the flag key. Without
it, every user would carry one fixed bucket across all flags, so the same 30% of users would be
the guinea pigs for every rollout in the system, and correlated failures would look like a single
broken cohort. With it, two flags at 30% overlap at roughly 9%, as independent selection implies.
Two flags given the same salt deliberately select the same cohort, which is how a feature spanning
a backend flag and a frontend flag rolls out to one consistent population.

**Monotone.** Raising a rollout never takes the flag away from someone who already had it, because
`bucket` does not depend on the percentage and the comparison is `<`. This is why increasing a
rollout is a safe operation rather than a reshuffle, and it is asserted by test (FR-EVL-008), not
assumed.

MurmurHash3 rather than SHA-256: it is not a security boundary, nobody gains anything by
predicting their own bucket, and it is several times faster on the hot path (ADR-003).

## 4. The ruleset cache

The serving API never assembles a response from the database per request (NFR-PER-004). The cache
and the two serving endpoints live in the `serving` package (ADR-024).

- On startup, before the application accepts requests, and after any write commits, the service
  rebuilds an immutable snapshot of the affected environment and swaps the reference atomically.
  A snapshot holds, for each key type, the engine's `Ruleset` and the JSON body `GET /sdk/config`
  sends. The client one carries client-side-visible flags only and no overrides field at all, and
  `POST /sdk/evaluate` evaluates a client key against that same filtered ruleset.
- A snapshot is built from four queries in one `REPEATABLE READ` transaction, so its version is
  the version of exactly the rows it was built from. Two rebuilds racing for one environment cannot
  move it backwards: the older version loses. A reconciliation every minute catches a rebuild that
  failed while the database was briefly away.
- Reads take the current reference. No locks on the read path.
- Each snapshot carries `environments.ruleset_version`, bumped in the write transaction. The ETag
  is derived from that version *and the key type*, because one URL serves a full ruleset to a
  server key and a filtered one to a client key. A version alone would let a client-key ETag
  validate a server-key request.

Authentication is cached the same way and for the same reason. A `/sdk/**` request that had to
look up `key_hash` in the database would make "the database is down and serving continues" false,
so key hashes live in an in-memory cache invalidated on issue and revoke (FR-KEY-007), and
`last_used_at` is flushed on a background schedule rather than on the request (FR-KEY-006).

Rebuilding the whole environment on any change is deliberately simple. At the scale this targets —
hundreds of flags, tens of environments — a full rebuild takes milliseconds, and it removes an
entire category of partial-invalidation bugs. If it ever becomes a bottleneck, that is a measured
problem to solve later, and the decision gets an ADR.

## 5. Change propagation

A write commits and the cache rebuilds the environment's snapshot before the request that made the
change returns. From there, in v0.1, the change reaches SDKs by polling: every five seconds each SDK
sends `GET /sdk/config` with the ETag of the ruleset it holds, and gets either an empty 304 or the
new ruleset, which it swaps in whole (FR-SDK-003). A change is in effect everywhere within about five
seconds. While the server is unreachable the SDK backs off with jitter up to 30 seconds and keeps
answering from the last ruleset (FR-SDK-004), so an outage degrades to staleness, never to errors.

**This is a cut, and it is stated as one.** The design is Server-Sent Events (ADR-004): one
long-lived connection per SDK carrying a version-only change event, which would make propagation
sub-second. It was the roadmap's first cut and was invoked for v0.1 (ADR-027), keeping the SDK, its
parity suite and the demo application, which are on the never-cut list. Five seconds rather than
the thirty ADR-004 rejects is the accepted degradation for a kill switch. When streaming is built it
uses `SseEmitter`, so connections do not each occupy a servlet thread, with a stated ceiling per key
and per environment (FR-STR-004), and polling stays as its fallback.

### One instance, and why that is written down

**The cache rebuild is in process** — as the stream registry will be. Run two instances behind a
load balancer and a write on instance A is not seen by instance B until B's once-a-minute
reconciliation compares versions with the database; until then every SDK polling B keeps getting
the old ruleset. For up to a minute, half the traffic would get a kill switch that has not killed,
silently, in a system that otherwise looks healthy.

So v0.x is a single instance, deliberately, and it is recorded in ADR-013 rather than left as a
property nobody stated. The intended fix is PostgreSQL `LISTEN`/`NOTIFY` on commit — no new
dependency, and the database is already in the transaction that needs observing — scheduled as
roadmap slice 4.8. Until it lands, do not scale this horizontally; the failure mode is stale flags
rather than an error, which is the worst kind to debug.

## 6. Tenant isolation

The dangerous failure in a multi-tenant system is a query that forgets its tenant filter. Relying
on every developer remembering is not a design.

Isolation is structural:

- A `TenantContext` reads the authenticated user from the JWT the security filter verified. An
  SDK request is authenticated by its key as one environment and never reads a tenant table.
- Tenant-scoped repositories take an `OwnerScope`, `ProjectScope` or `EnvironmentScope` rather
  than an id, and every query includes it. The scopes have no public constructor: only
  `TenantResolver` creates one, by a query carrying the scope above it, so a service cannot hold a
  project it was not shown to own. There is no repository method that returns rows across
  projects, and no inherited `findById` or `findAll`.
- An interceptor resolves the `{projectKey}`, `{envKey}` and `{flagKey}` a path names before the
  request body is read, so another tenant's resource is a 404 even where the body would have been
  a 400.
- Cross-tenant access returns 404 rather than 403, so existence is not disclosed (FR-PRJ-003).
- A dedicated test suite attempts isolation breaks from every endpoint and asserts failure
  (`docs/TESTING.md`).

## 7. Failure behaviour

| Failure | Behaviour |
| --- | --- |
| Malformed rule | Refused when written (FR-RUL-010). One that reaches evaluation anyway resolves the flag to `fallthroughValue` with reason `ERROR`, warning logged (rate limited); a rule that matched before it still wins (ADR-020) |
| Unknown flag key | Caller's fallback returned, with reason `FLAG_NOT_FOUND`. Never a 404 |
| Missing user key | Overrides and rollout skipped, rules still evaluated, then `fallthroughValue` |
| Engine exception | `fallthroughValue` returned, warning logged, never propagated |
| Database down | Serving continues from cache, including key authentication; management API returns 503; readiness stays up. One exception: the first management request to borrow a pooled connection that died with the database gets a 500, because the pool reports only that the connection is closed |
| Flaglane unreachable from SDK | Last known ruleset, then code-level fallback |
| A poll fails | Last ruleset kept; retried with exponential backoff and jitter up to 30 s, or after a 503's `Retry-After` |

The last two are the product's central promise. Everything else in this document is negotiable.

## 8. Deployment

`docker compose up` runs API, PostgreSQL and dashboard. The dashboard is not a third service: the
image builds it and the API serves it from its own origin, so the dashboard never makes a
cross-origin request and `/api/**` admits none (ADR-031). The published image takes all
configuration from environment variables and runs Flyway on startup.

Two database roles, not one: `flaglane_migrator` owns the schema and runs Flyway at startup,
`flaglane_app` runs the application and holds no DDL privileges and no write privileges on
`audit_entries`. Compose, production and the Testcontainers fixture all provision both, because an
append-only guarantee that only holds when nobody is a superuser is not a guarantee.

Liveness is `/actuator/health/liveness` and ignores the database. Readiness is
`/actuator/health/readiness` and requires the ruleset cache only. Database health is a separate
indicator that gates no traffic (NFR-REL-003) — an outage must not get the serving path evicted
by its own orchestrator.
