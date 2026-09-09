# Architecture

## 1. Shape of the system

```
Dashboard (React)  ──JWT──►  Management API  ─┐
                                              ├─►  Service layer  ──►  PostgreSQL
Your application               Serving API  ──┘         │
  └─ Flaglane SDK  ──API key──►     │                   │
         │                          ▼                   ▼
         └────── SSE stream ◄── Ruleset cache ◄── change events
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
memory, and receives updates over a stream. Consequences:

- Evaluation is a hash and a few comparisons — microseconds, no I/O.
- Flaglane can be down and applications keep working on the last ruleset.
- The rules are visible to the client, so a client key must only receive client-visible flags
  (FR-KEY-005).
- Changes are not instant by default, so a push channel is required (FR-STR-001).

`POST /sdk/evaluate` exists for thin clients that cannot hold a ruleset, and runs the identical
engine server-side.

## 3. The evaluation engine

`io.github.sanduniliyanage.flaglane.evaluation` is plain Java. No Spring, no JPA, no database.
It is a pure function:

```
evaluate(Ruleset, flagKey, UserContext, fallback) -> Value
```

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
`murmur3_32()` is not — it mishandles non-ASCII input.

Four properties matter:

**Deterministic.** The same user must get the same answer on every server, after every restart,
for the life of the flag. If it changed, a user would watch the interface flicker between two
versions. This rules out random numbers, and it rules out anything seeded by process start, host
identity, or wall-clock time.

**Uniform.** A 30% rollout must reach approximately 30% of users. MurmurHash3 distributes evenly
across the 0–99 range for realistic user key distributions, and this is asserted by test, not
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

The serving API never assembles a response from the database per request (NFR-PER-004).

- On startup, and after any write, the service rebuilds an immutable `Ruleset` snapshot per
  environment and swaps the reference atomically.
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

A write commits, the cache rebuilds, and a change event publishes to the stream registry, which
holds open SSE connections keyed by environment.

Server-Sent Events rather than WebSockets: the traffic is one-directional, SSE is plain HTTP so it
crosses proxies without upgrade negotiation, and browsers reconnect automatically (ADR-004). The
SDK also polls as a fallback, so a blocked stream degrades to eventual consistency rather than
staleness forever (FR-SDK-004).

## 6. Tenant isolation

The dangerous failure in a multi-tenant system is a query that forgets its tenant filter. Relying
on every developer remembering is not a design.

Isolation is structural:

- A `TenantContext` is populated by the security filter from the JWT or API key.
- Tenant-scoped repositories take the scope as a parameter and every query includes it. There is
  no repository method that returns rows across projects.
- Cross-tenant access returns 404 rather than 403, so existence is not disclosed (FR-PRJ-003).
- A dedicated test suite attempts isolation breaks from every endpoint and asserts failure
  (`docs/TESTING.md`).

## 7. Failure behaviour

| Failure | Behaviour |
| --- | --- |
| Malformed rule | Skipped, warning logged (rate limited), evaluation continues |
| Unknown flag key | Caller's fallback returned, with reason `FLAG_NOT_FOUND`. Never a 404 |
| Missing user key | Overrides and rollout skipped, rules still evaluated, then `fallthroughValue` |
| Engine exception | `fallthroughValue` returned, warning logged, never propagated |
| Database down | Serving continues from cache, including key authentication; management API returns 503; readiness stays up |
| Flaglane unreachable from SDK | Last known ruleset, then code-level fallback |
| Stream dropped | Backoff reconnect with jitter, polling meanwhile |

The last two are the product's central promise. Everything else in this document is negotiable.

## 8. Deployment

`docker compose up` runs API, PostgreSQL and dashboard. The published image takes all
configuration from environment variables and runs Flyway on startup.

Two database roles, not one: `flaglane_migrator` owns the schema and runs Flyway at startup,
`flaglane_app` runs the application and holds no DDL privileges and no write privileges on
`audit_entries`. Compose, production and the Testcontainers fixture all provision both, because an
append-only guarantee that only holds when nobody is a superuser is not a guarantee.

Liveness is `/actuator/health/liveness` and ignores the database. Readiness is
`/actuator/health/readiness` and requires the ruleset cache only. Database health is a separate
indicator that gates no traffic (NFR-REL-003) — an outage must not get the serving path evicted
by its own orchestrator.
