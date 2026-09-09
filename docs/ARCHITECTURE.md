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

1. **Kill switch.** Configuration disabled → `defaultValue`. Nothing else is consulted.
2. **User override.** Exact match on user key → its value.
3. **Targeting rules.** Priority ascending, first match wins → its result value.
4. **Percentage rollout.** `bucket < rolloutPercentage` → `true`.
5. **Default.** `defaultValue`.

Order matters and is not arbitrary. Overrides sit above rules so an engineer can put themselves
in a feature that is at 0%. The kill switch sits above everything so the 2am rollback needs no
reasoning about rules.

### Bucketing (FR-EVL-002 to FR-EVL-004)

```
bucket = murmur3_32(flagKey + ":" + userKey, seed = 0) unsigned % 100
```

Three properties matter:

**Deterministic.** The same user must get the same answer on every server, after every restart,
for the life of the flag. If it changed, a user would watch the interface flicker between two
versions. This rules out random numbers, and it rules out anything seeded by process start, host
identity, or wall-clock time.

**Uniform.** A 30% rollout must reach approximately 30% of users. MurmurHash3 distributes evenly
across the 0–99 range for realistic user key distributions, and this is asserted by test, not
assumed.

**Independent per flag.** The flag key is part of the hash input. Without it, every user would
carry one fixed bucket across all flags, so the same 30% of users would be the guinea pigs for
every rollout in the system, and correlated failures would look like a single broken cohort.
With it, two flags at 30% overlap at roughly 9%, as independent selection implies.

MurmurHash3 rather than SHA-256: it is not a security boundary, nobody gains anything by
predicting their own bucket, and it is several times faster on the hot path (ADR-003).

## 4. The ruleset cache

The serving API never assembles a response from the database per request (NFR-PER-004).

- On startup, and after any write, the service rebuilds an immutable `Ruleset` snapshot per
  environment and swaps the reference atomically.
- Reads take the current reference. No locks on the read path.
- Each snapshot carries a version, exposed as an ETag, so `GET /sdk/config` answers 304 cheaply.

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
| Malformed rule | Skipped, warning logged, evaluation continues |
| Unknown flag key | Caller's fallback returned |
| Missing user key | Rules and rollout skipped, default returned |
| Engine exception | Default returned, warning logged, never propagated |
| Database down | Serving continues from cache; management API returns 503 |
| Flaglane unreachable from SDK | Last known ruleset, then code-level fallback |
| Stream dropped | Backoff reconnect with jitter, polling meanwhile |

The last two are the product's central promise. Everything else in this document is negotiable.

## 8. Deployment

`docker compose up` runs API, PostgreSQL and dashboard. The published image takes all
configuration from environment variables and runs Flyway on startup. Health and readiness are on
`/actuator/health`.
