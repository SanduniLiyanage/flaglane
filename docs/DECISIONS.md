# Decision log

One entry per decision with lasting consequences. Append; never edit a decided entry. If a
decision is reversed, add a new entry that supersedes it and say why.

Format: context, decision, consequences, alternatives rejected.

---

## ADR-001 — Java 21 and Spring Boot 3 for the backend

**Context.** The service needs a mature HTTP stack, a solid PostgreSQL story, first-class
Testcontainers support, and a large pool of developers who can read it.

**Decision.** Java 21, Spring Boot 3, Gradle.

**Consequences.** JVM startup and image size are larger than a Go binary, which matters for a
self-hosted tool. Accepted: `docker compose up` is still one command, and the ecosystem depth is
worth more here than image size.

**Rejected.** Go — smaller and faster to start, but a new language on a four-week schedule alongside
an unfamiliar domain is two risks at once. Node — the SDK is TypeScript already, and running both
sides in one language would have hidden parity bugs behind shared code rather than exposing them.

---

## ADR-002 — In-process evaluation as the primary model

**Context.** Flag checks sit inside customer request paths.

**Decision.** SDKs download the full ruleset and evaluate locally. Remote evaluation exists as a
secondary endpoint for thin clients.

**Consequences.** Evaluation costs microseconds and no network. Flaglane's availability is
decoupled from customers' availability. Rules become visible to the client, which forces the
server/client key distinction. Changes need a push channel to be timely.

**Rejected.** Server-side evaluation only — simpler and hides rules, but puts a network round trip
in every customer request and makes this service a hard dependency of theirs.

---

## ADR-003 — MurmurHash3 with the flag key in the hash input

**Context.** Bucketing must be deterministic, uniform, and independent across flags.

**Decision.** `murmur3_32(flagKey + ":" + userKey, seed = 0) unsigned % 100`.

**Consequences.** Fast on the hot path. Two flags at the same percentage select different
populations. Buckets are predictable to anyone who knows the algorithm, which is fine — this is
not a security boundary.

**Rejected.** SHA-256 — cryptographic strength buys nothing here and costs measurable time.
`userKey` alone without the flag key — would give each user one fixed bucket for every flag in the
system, so the same cohort would be the test population for every rollout forever.

---

## ADR-004 — Server-Sent Events for change propagation

**Context.** Flag changes must reach connected SDKs in under a second.

**Decision.** SSE from server to SDK, with polling as a fallback.

**Consequences.** Plain HTTP, so it traverses proxies and load balancers without upgrade
negotiation. Browsers reconnect natively. Long-lived connections need heartbeats and a per-key
connection limit.

**Rejected.** WebSockets — bidirectional, which this traffic is not, and more infrastructure
friction for no gain. Polling alone — a 30-second worst case is unacceptable for a kill switch.

---

## ADR-005 — Apache License 2.0

**Context.** The intended users are teams inside companies, which means a legal review before
adoption.

**Decision.** Apache 2.0.

**Consequences.** The explicit patent grant is what lets a company adopt this without escalation.
Slightly more ceremony than MIT.

**Rejected.** MIT — simpler, but no patent grant, which is precisely what corporate reviewers
look for. GPL — would prevent the exact adoption this project is for.

---

## ADR-006 — Two separate API surfaces

**Context.** The dashboard and SDKs share a service but nothing else.

**Decision.** `/api/**` with JWT for the dashboard, `/sdk/**` with API keys for SDKs. Separate
security filter chains, rate limits and error semantics.

**Consequences.** Some duplication in controllers. In exchange, the high-traffic path can be
rate limited, cached and degraded independently, and a mistake in dashboard auth cannot widen
SDK access.

**Rejected.** One surface with mixed auth — smaller, but couples two workloads with opposite
requirements and makes "never fail the serving path" impossible to reason about.

---

## ADR-007 — API keys hashed at rest, displayed once

**Context.** A leaked key exposes an environment's flags.

**Decision.** Store SHA-256 of the key and a short non-secret prefix. Show plaintext once at
creation.

**Consequences.** A lost key is regenerated, not recovered. The prefix gives the UI something to
display. No reversible secret sits in the database.

**Rejected.** Encrypted at rest — introduces a key-management problem to solve a problem that
does not exist, since Flaglane never needs to read the key back.

---

## ADR-008 — Full ruleset rebuild on write

**Context.** The serving path must not query the database (NFR-PER-004), so a cache is required,
and caches need invalidation.

**Decision.** Rebuild the entire environment ruleset on any write and swap the reference
atomically.

**Consequences.** Trivially correct: no partial-invalidation bugs are possible. Costs more work
per write than a targeted update, which is irrelevant at hundreds of flags and human-rate writes.

**Rejected.** Per-flag invalidation — faster on paper, and the source of a whole class of bugs
where the cache and database disagree. If write volume ever justifies it, that reversal gets its
own entry here, with the measurement that motivated it.
