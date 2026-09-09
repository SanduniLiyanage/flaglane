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

**Amended by ADR-010 and ADR-011.** The decision to include the flag key stands and the reasoning
above is unchanged. ADR-010 replaces the literal flag key in the hash input with a salt that
defaults to it; ADR-011 replaces `% 100` with `% 10000`. Both are recorded separately rather than
edited into this entry, because what changed is worth reading on its own.

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
does not exist, since Flaglane never needs to read the key back. bcrypt or Argon2 — the right
tools for a secret a human chose, and unnecessary cost per request for one this service generated.

**Amended.** That last argument was the load-bearing premise and was left unstated: SHA-256 is
correct here *only because* keys are high-entropy random values. A key is 32 bytes from a CSPRNG,
so brute force is not on the table and a slow hash buys nothing but latency on every SDK request.
The format that makes this true is now specified in `docs/DATABASE.md` rather than left to the
implementation to get right by accident.

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

---

## ADR-009 — `offValue` and `fallthroughValue` are different fields

**Context.** A configuration had one `defaultValue`, returned both when the flag was disabled and
when an enabled flag matched nothing. `defaultValue` is editable from the dashboard.

**Decision.** Split it. A disabled configuration returns `offValue`. An enabled configuration that
matches no override, no rule and no rollout returns `fallthroughValue`. `offValue` is fixed `false`
in v0.x and is not exposed for editing; the column exists so that making it configurable later is a
value change rather than a migration.

**Consequences.** The kill switch is unconditional: disabling a flag can only ever turn a feature
off, whatever else is configured. The rollout step stops being incoherent — `bucket < rollout →
true` means something now that the fallthrough can be `false` independently. One extra column, one
extra field on the wire, and a dashboard that has to explain that a `true` fallthrough makes the
rollout inert.

**Rejected.** Keeping one field and forbidding `defaultValue: true` — the same restriction with no
way to express "on for everyone", and it would have to be enforced in service code rather than by
the shape of the data. Making `offValue` editable in v0.x — reintroduces the original failure for
anyone who sets it, in exchange for a capability nobody asked for.

---

## ADR-010 — The rollout salt is configurable, defaulted to the flag key

**Context.** ADR-003 puts the flag key in the hash input so two flags at the same percentage select
different populations. That is right, and it forecloses the opposite requirement: a feature that
spans a backend flag, a frontend flag and a migration flag needs all three to select the *same*
users, or the difference between the cohorts ships as an inconsistent experience.

**Decision.** Hash `rolloutSalt + ":" + userKey`, where `rolloutSalt` is a column on
`flag_configs` defaulted to the flag key at creation.

**Consequences.** Identical behaviour on day one; suite 3 asserts the same result. Coordinated
rollout becomes a field in the dashboard instead of a change nobody can safely make. Changing a
salt re-buckets every user of that flag, so the endpoint and the UI say so explicitly. The parity
suite gains a fixture with two flags sharing a salt.

**Rejected.** Doing this later — changing the hash input reshuffles every user's bucket on every
flag, which is precisely the interface flicker FR-EVL-003 exists to prevent. There is no safe
migration, so this is a decision that can only be taken before the first flag exists. A separate
`cohortKey` field alongside the flag key — two concepts where one salted string does the work.

---

## ADR-011 — Buckets are basis points; the interface stays in whole percentages

**Context.** `% 100` fixes rollout granularity at one percent. A 0.1% canary on a risky change is
ordinary practice and would be inexpressible. Moving to `% 10000` later reshuffles every user, for
the same reason as ADR-010.

**Decision.** `bucket` is 0–9999. `flag_configs.rollout_basis_points` is an integer 0–10000. The
management API and the dashboard accept and display an integer percentage 0–100 and multiply by
100 on the way in. The ruleset served to SDKs carries basis points, so the SDK never converts.

**Consequences.** Sub-percent rollouts are available whenever the UI decides to expose them,
without touching bucketing. CLAUDE.md's rule that percentages are integers holds on both sides of
the conversion — there is no float anywhere in the path. Two units exist in the system, so every
field name says which one it is: `rolloutPercentage` on the management API, `rolloutBasisPoints`
in the ruleset and the database.

**Rejected.** Keeping `% 100` and widening later — a one-line change today, an unmigratable one
after the first production rollout. Storing a decimal percentage — floats in a value that decides
who sees a feature, compared with `<` across two languages' rounding.

---

## ADR-012 — No refresh tokens in v0.x

**Context.** Sign-in issued an access token and a refresh token, and `POST /api/auth/refresh`
exchanged one for the other. Nothing said whether refresh tokens were stateless JWTs or persisted
rows, and no table existed for them. If they are stateless, signing out clears the browser and
leaves the refresh token valid until expiry, so a stolen token survives the one action a user takes
when they think they have been compromised.

**Decision.** One access token, a JWT valid for 8 hours. No refresh token, no refresh endpoint.
Sign-out discards the token client-side.

**Consequences.** Sign-out does not invalidate anything server-side, and this is written down in
`SECURITY.md` and in FR-UI-001 instead of being implied by an endpoint that appears to do more than
it does. Users sign in once a working day. There is no revocation for a stolen token inside its
lifetime, which is the honest cost of not building the table.

**Rejected.** A `refresh_tokens` table, hashed, with `revoked_at` — the correct answer, and it is
what v0.2 should build; it needs a requirement, a schema row, a rotation policy and a reuse-
detection rule, and half of that shipped is worse than none. Stateless refresh tokens with a short
lifetime — the same exposure as a long access token, with an endpoint that implies revocation
exists.
