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

**Rejected.** Go — smaller and faster to start, but a new language alongside an unfamiliar domain
is two risks at once. Node — the SDK is TypeScript already, and running both
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

**Amended, twice.**

*Implementation.* Streams use `SseEmitter`, which releases the request thread. A blocking
implementation would cap concurrent streams at Tomcat's ~200 threads, which is a connection ceiling
nobody chose. The ceiling that is chosen: 500 concurrent streams per key and 1,000 per environment,
configurable, refused with 429 above that (FR-STR-004). "A per-key connection limit" above named no
number, and an unnumbered limit is not a limit.

*The cut list.* If SSE is cut under time pressure, the fallback is 5-second polling, not the
30-second polling this entry rejects. A 30-second worst case on a kill switch is unacceptable
whether it arrives by design or by triage; five seconds is an accepted degradation, and it is
recorded here so that the first thing on the cut list does not silently contradict a decision.

**Amended by ADR-027.** The cut was invoked for v0.1: there is no stream, and SDKs poll every five
seconds. The decision above stands as the design for when streaming is built.

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

---

## ADR-013 — v0.x runs as a single instance

**Context.** The ruleset cache is rebuilt in process on write (ADR-008), and the SSE registry holds
open connections in process (ARCHITECTURE section 5). Run two instances behind a load balancer and
a write on instance A never reaches instance B: B serves a stale cache until it restarts, and every
SDK connected to B's stream never learns that anything changed. The kill switch does not kill for
half the traffic, and NFR-PER-003 fails silently rather than loudly. Nothing in the documentation
said so, while "self-hostable" is an invitation to scale it.

**Decision.** v0.x runs as exactly one API instance. This is stated in the README, in
`docs/ARCHITECTURE.md` and in the requirements it qualifies, rather than left to be discovered by
whoever first sets `replicas: 2`.

**Consequences.** No horizontal scaling and no rolling deploy without a propagation gap: during a
restart, the new instance serves from a freshly built cache and the old one is gone, which is fine,
but two overlapping instances are not. In exchange, every propagation guarantee in the
specification is true as written for the deployment the product actually supports. Rate limiting
buckets and the stream connection ceiling are per instance, which is exact under this constraint
and approximate the moment it lifts.

**The intended fix**, scheduled as slice 4.8: PostgreSQL `LISTEN`/`NOTIFY` on commit. The write
transaction issues `NOTIFY ruleset_changed, '<environment id>'`; every instance listens, invalidates
its cache for that environment and fans the change out to its own stream registry. No new
dependency — the database is already there and already carries the transaction that has to be
observed. It is roughly forty lines and it is not in v0.x only because it has not been built and
tested, not because it is hard.

**Rejected.** Saying nothing and hoping — the failure is silent, it looks exactly like a working
system, and it fails hardest at the moment the kill switch is being used. Redis pub/sub — a second
piece of infrastructure for every self-hoster, to do what the database can already do. Sticky
sessions — does not help; the problem is the write, not the reader.

---

## ADR-014 — Java 25 supersedes ADR-001's version choice

**Context.** ADR-001 decided Java 21 before any machine was set up to build the project. At the
start of implementation, the installed JDK is Java 25 — the LTS release available at that point.
ADR-001's reasoning (a mature HTTP stack, a solid PostgreSQL story, first-class Testcontainers
support, a large pool of developers who can read it) was about the language and ecosystem, not
about which specific LTS number was current, and none of it depended on 21 rather than a later LTS.

**Decision.** The backend targets Java 25. Everywhere CLAUDE.md, the roadmap and other documents
name a Java version, it is 25. ADR-001 stands as the historical record of the decision to use Java
and Spring Boot at all; this entry supersedes only its version number, not its reasoning.

**Consequences.** None beyond the version string: Spring Boot 3.x, Gradle and the rest of ADR-001's
stack are unaffected. Toolchains, CI images and local setup instructions must specify 25, not 21.

**Rejected.** Editing ADR-001 in place — this log is append-only by its own stated rule; a decided
entry is superseded by a new one, not rewritten.

---

## ADR-015 — SpotBugs for static analysis

**Context.** Roadmap slice 1.1 named three candidates — SpotBugs, Error Prone and PMD — three
dependencies with three failure modes, and CLAUDE.md requires a decision before adding one. Error
Prone runs as a javac plugin and couples static analysis to the compiler itself, which makes a
false positive a build-breaking compile error rather than a separate, reviewable report. PMD and
SpotBugs both run as an independent Gradle task against compiled output, which keeps analysis out
of the compiler's way.

**Decision.** SpotBugs, via the `com.github.spotbugs` Gradle plugin, wired into `check`.

**Consequences.** Bytecode-level analysis catches a class of bug PMD's source-level rules do not
— null dereferences, resource leaks, and equality mistakes that survive to compiled output. The
plugin's own tool classpath (configuration `spotbugs`, distinct from the project's dependencies)
pulls a newer `commons-lang3` than the one `io.spring.dependency-management` forces onto every
configuration by default, which breaks analysis with `NoClassDefFoundError: org/apache/commons/
lang3/Strings` unless the `spotbugs` configuration's resolution is pinned back to the version
SpotBugs actually needs. `backend/build.gradle.kts` does this explicitly, with a comment, so the
next person who bumps SpotBugs's version knows why the override exists and needs to re-check it
rather than delete it as dead code.

**Rejected.** Error Prone — analysis-as-compiler-plugin makes every finding a compile failure, no
separate report to triage. PMD — source-level only, and adding it later alongside SpotBugs remains
open rather than foreclosed by this entry.

---

## ADR-016 — Database roles are provisioned by the environment, privileges by migrations

**Context.** `docs/DATABASE.md` requires two roles: `flaglane_migrator`, which owns the schema and
runs Flyway, and `flaglane_app`, which runs the application without DDL and without write access
to `audit_entries`. The migration plan originally had `V2__roles.sql` create `flaglane_app`. A
`CREATE ROLE ... LOGIN` needs a password, a migration is source, and source carries no secrets
(CLAUDE.md). Creating the role without a password and letting the environment set one later
splits the definition of one role across two places that must agree, and puts a role that
cannot log in into every database the migration ever touches.

**Decision.** The environment creates both roles and owns their passwords: Compose and the
Testcontainers fixture through `docker/postgres/init-roles.sh`, a managed PostgreSQL through the
provider's tooling. `flaglane_migrator` owns the database, which makes it the owner of the
`public` schema through `pg_database_owner`. Migrations run as `flaglane_migrator` and grant
privileges to `flaglane_app` by name; they never create a role and never carry a credential.
The application receives the two credentials as environment variables with no defaults.

**Consequences.** `V2__roles.sql` shrinks to grants and revokes. The role names are a fixed part
of the schema contract, because the grants reference them; the passwords are not, and rotating
one is an operational change with no migration. A fresh database that has not been provisioned
fails at the first `GRANT` with the role named, which is the right place to fail. The fixture
and the Compose stack share one script, so a change to the provisioning cannot leave the tests
running under a different privilege split from the deployment. Until V2 lands, `flaglane_app`
can connect and resolve names in `public` and nothing else, which is enough for the health
endpoint and nothing more.

**Rejected.** Roles created by migration with a placeholder password — a credential in source,
even a placeholder, is the thing the rule exists to prevent, and the placeholder would be live
in any database where nobody remembered to change it. One role for both migrations and the
application — the owner of `audit_entries` can grant itself the privileges the revoke removed,
which `docs/DATABASE.md` already rejects. Superuser for the application — same objection,
stronger.

---

## ADR-017 — Numbers compare as doubles; an empty user key is no user key

FR-RUL-007 makes comparison type-strict but does not say what "equal" means between two numbers,
and FR-EVL-005 does not say whether `""` counts as a supplied user key; Java and JavaScript answer
both differently by default, so the engine decides here and the SDK copies it. **Decision:** every
number is an IEEE-754 double, as every number in the TypeScript SDK already is, so `1` equals `1.0`,
`-0` equals `0`, integers above 2^53 collapse exactly as `JSON.parse` collapses them, and NaN and
infinities are not values at all. An empty user key is treated as absent, so overrides and rollout
are skipped for it rather than every caller that sent `""` landing in one shared bucket.
**Consequences:** Java never needs `BigDecimal` to agree with JavaScript, and a key-less caller
behaves the same whether it omitted the key or sent it empty. **Rejected:** exact decimal
comparison — it would make `1` and `1.0` differ in Java and agree in TypeScript, the precise kind of
silent divergence E-011 exists to prevent; hashing `""` like any other key — every anonymous caller
would share one bucket and switch on or off together.

---

## ADR-018 — An unpaired surrogate in a user key hashes as U+FFFD

FR-EVL-002 hashes the UTF-8 bytes of `rolloutSalt + ":" + userKey`, but a JavaScript or Java string
can hold an unpaired UTF-16 surrogate — a key cut through the middle of an emoji, or a JSON `\ud83d`
escape — and such a string has no UTF-8 encoding at all. Each runtime substitutes something:
`TextEncoder` and Node's `Buffer` write U+FFFD (`EF BF BD`), while Java's `String.getBytes` writes
`?`, so the same key lands in different buckets on the server and in the SDK. **Decision:** the
server encodes each unpaired surrogate as U+FFFD, matching the WHATWG encoder the SDK cannot avoid
using; well-formed strings take the ordinary `getBytes` path unchanged. **Consequences:** both
implementations bucket every string identically, malformed ones included, at the cost of one scan
of the key for surrogates. Two keys that differ only in which lone surrogate they carry share a
bucket, which is harmless. **Rejected:** rejecting such keys — the evaluation path never throws and
would have to pick a bucket anyway; leaving Java's `?` — the SDK would need a custom encoder to
match it, in every runtime it ever ships for.

---

## ADR-019 — Operator shapes, and negative operators as exact negations

FR-RUL-002 gives every rule a value list and FR-RUL-007 to FR-RUL-009 define equality, absence and
the string-only operators, but nothing says what `EQUALS` does with two values, or whether
`NOT_EQUALS` matches an attribute of another type; both are questions the SDK will otherwise answer
on its own. **Decision:** `EQUALS`, `NOT_EQUALS`, `CONTAINS`, `STARTS_WITH` and `ENDS_WITH` take
exactly one match value; `IN` and `NOT_IN` take one or more, all of one type. A rule outside those
shapes — or with an operator name not spelled exactly as in FR-RUL-003, no attribute, or a match
value that is not a string, number or boolean — is malformed. For an attribute that is present,
`NOT_EQUALS` and `NOT_IN` are the exact negations of `EQUALS` and `IN`, so `1 NOT_EQUALS "1"`
matches, because under FR-RUL-007 the two are not equal; absence is the only special case
(FR-RUL-008), and an attribute of an unsupported type counts as absent (FR-RUL-006). The cases live
in `backend/src/test/resources/fixtures/comparison-semantics.json`, which both implementations run.
**Consequences:** management API validation (FR-RUL-010) has an exact definition to enforce, and
"not equal" means not equal. **Rejected:** "any of" semantics for several values on a
single-valued operator — reasonable, but adopting it later changes the meaning of no stored rule,
whereas retreating from it would; a wrongly typed attribute never matching any operator — it would
make `NOT_EQUALS` mean something narrower than its name, in a way no reader of FR-RUL-007 would
guess.

---

## ADR-020 — A malformed rule resolves the flag to its fallthrough value

The documents disagreed. `docs/ARCHITECTURE.md` section 7 says a malformed rule is skipped and
evaluation continues; CLAUDE.md, FR-EVL-006 and suite 7 say it resolves to `fallthroughValue`. The
two give different answers whenever a later rule or the rollout would have matched. **Decision:**
a malformed rule that evaluation reaches resolves the flag to its `fallthroughValue` with reason
`ERROR`. Evaluation stops there; a rule that matched before it still wins, and an override or the
kill switch never reaches it. **Consequences:** a broken rule can only ever produce the value the
flag's owner chose as its resting state, never a value some later step happened to compute. Section
7 of the architecture document is to be corrected to match. **Rejected:** skipping the rule — if the
broken rule was the one excluding a group, say `country IN ["XX"] → false`, skipping it hands that
group whatever the rollout gives them, which is exactly the population the rule existed to keep out.

---

## ADR-021 — Dashboard tokens through Spring Security's resource server, HS256

FR-ACC-002 needs a JWT issued at sign-in and verified on every `/api/**` request, and the stack named
no library for it. **Decision:** `spring-boot-starter-oauth2-resource-server`, whose Nimbus
integration is managed by the Spring Boot BOM: verification is Spring Security's own bearer token
filter with a decoder pinned to HS256 that requires `exp`, checks it against the injected `Clock`
with 30 seconds of skew, and requires `iss` to be `flaglane`; issuance is the matching `JwtEncoder`.
The key is one symmetric secret from `FLAGLANE_JWT_SECRET`, at least 32 bytes, with no default.
Alongside it: email addresses are stored trimmed and lower-cased, so one person cannot hold two
accounts by capitalisation; a new password must be at least 15 characters (NIST SP 800-63B-4's
floor for a single factor) and at most 72 bytes of UTF-8, the most bcrypt reads, with no
composition rules; and registration writes no audit entry, because `audit_entries.project_id` is
not nullable and the action vocabulary has no account event — FR-AUD-001 is the record of changes
to projects, which the SRS will state as erratum E-037. **Consequences:** no JWT code of Flaglane's
own and no second JWT library. One secret both signs and verifies, which is sound while the issuer
and the verifier are the same single instance (ADR-013); rotating it signs every user out. The
password limits are the easy ones to relax later: loosening breaks no stored password, tightening
would. **Rejected:** jjwt — a second library for what the starter already carries; RS256 — a key
pair to manage for a token only this process ever reads; server-side sessions — the dashboard API is
stateless by design, and revocation is ADR-012's deferred work, not this one's.

---

## ADR-022 — Audit entries keep the id of a deleted environment rather than losing it

FR-ENV-003 and `docs/DATABASE.md` had deleting an environment set `audit_entries.environment_id` to
NULL through `ON DELETE SET NULL`, while FR-AUD-002 and `V3__audit_append_only.sql` make every
UPDATE of `audit_entries` fail. The referential action is an UPDATE, so the trigger refused it, and
no environment that had ever been audited — which is every environment — could be deleted; this was
reproduced against PostgreSQL before slice 1.7 built the endpoint. **Decision:**
`V4__audit_keeps_deleted_references.sql` drops the foreign keys from `audit_entries.environment_id`
and `audit_entries.flag_id`. An entry keeps the id of what it describes after that is deleted, and
the append-only trigger stays absolute, with no exception for any role or any path. **Consequences:**
the audit trail keeps more than it did: a deleted environment's entries still say which environment
they were about, and their payloads carry its key. Those two columns are no longer checked by the
database on insert; the service writes them in the same transaction as the change they describe.
FR-ENV-003's "blanks the environment reference" becomes "keeps it", to be recorded as an SRS
erratum alongside the `docs/DATABASE.md` foreign key table. **Rejected:** narrowing the trigger to
let the referential SET NULL through — it would hold, but the one guarantee a trigger exists to give
would carry an exception; soft-deleting environments — every environment query would have to filter
deleted rows forever, to preserve a NULL that says less than the id it replaces.

---

## ADR-023 — Upper bounds on what one configuration may hold

Nothing bounded how many rules or overrides a configuration could carry, yet every one of them is
in the ruleset each SDK downloads and walked on the hot path, and NFR-PER-001 is stated at 50 rules
on the flag under evaluation. **Decision:** the management API refuses more than 100 rules per
configuration, more than 1,000 match values per rule, attribute names over 100 characters, more
than 1,000 user overrides per configuration, and user keys over 256 characters. **Consequences:**
the ruleset and the evaluation cost of one flag have a known ceiling, at twice the rule count the
performance requirement is measured at. A team that genuinely needs more overrides is pushed
towards a targeting rule on an attribute, which is what scales. **Rejected:** no limits — the
ruleset is downloaded by every SDK, and an unbounded one is a denial of service by configuration;
tighter limits now — raising a limit later breaks nothing, lowering one would invalidate stored
configurations, so the starting point errs generous.

---

## ADR-024 — A `serving` package for the ruleset cache and the serving endpoints

CLAUDE.md's module layout names `evaluation/` for the engine and `streaming/` for SSE, but nothing
for the ruleset cache of `docs/ARCHITECTURE.md` section 4 or for `GET /sdk/config` and `POST
/sdk/evaluate`. The cache reads every feature's tables and the endpoints read only the cache, so
none of `flag/`, `targeting/` or `streaming/` is their natural owner, and `evaluation/` may import
nothing. **Decision:** a `serving/` feature package, laid out like the others, holds the ruleset
loader, the cache, its readiness indicator and the two serving endpoints; `streaming/` will read
the same cache when it arrives. **Consequences:** the read side of the system — everything an SDK
touches — is in one place, separate from the write side it is built from, which is the separation
ADR-006 and NFR-PER-004 already describe. **Rejected:** folding the cache into `flag/` — it would
make the flag package depend on targeting and keys, and put the hot path inside a write-side
package; folding it into `streaming/` — serving must work with streaming cut (the roadmap's first
cut), so it cannot live in the package that cut would remove.

---

## ADR-025 — The serving API accepts cross-origin requests from any origin

Client keys exist so that a browser can download its environment's ruleset (FR-KEY-005), but
`/sdk/**` sent no CORS headers, so a page on any other origin than the API's could not read the
response, and a browser SDK could not read the ETag it needs for conditional requests.
**Decision:** the `/sdk/**` chain allows `GET` and `POST` from any origin, with the
`Authorization`, `If-None-Match` and `Content-Type` request headers, exposes `ETag` and
`Retry-After`, and never allows credentials. The dashboard API's chain allows no cross-origin
request at all. **Consequences:** a client key works from whichever site embeds it, which is what a
publishable key is. Allowing every origin grants no site anything: the key travels in a header the
page sets itself rather than in a cookie a browser attaches on its own, so another site can do with
the API only what anyone holding the key already could, and the key's own type limits that to
client-side-visible flags with no overrides (FR-KEY-008). **Rejected:** a per-key list of allowed
origins — it protects nothing a public key can be used for, since the key can be called from a
server or a script outside any browser, and it is a setting every user would have to get right
before the SDK worked at all; allowing credentials — there are none to allow, and enabling them would
forbid the wildcard.

---

## ADR-026 — The TypeScript SDK: no dependencies, polling, and nothing that can fail the caller

The SDK needed a toolchain, a public shape and an update mechanism, and the stack named only
"TypeScript, published to npm". **Decision:** `@flaglane/sdk` is ES modules only, compiled by
TypeScript 7's `tsc` and tested with Vitest 5, with no runtime dependencies: it uses `fetch`,
`TextEncoder` and `AbortController`, so one build runs in browsers and in Node 20 and later. Its
engine mirrors the server's definition for definition, including what counts as a malformed rule,
and is held to it by the shared fixtures of suite 9. The context is flat — `{ key, ...attributes }`,
as the README always showed — and `isOn(flagKey, context, fallback = false)` evaluates in memory.
Updates arrive by polling `GET /sdk/config` every five seconds with the ruleset's ETag, so an
unchanged ruleset costs an empty `304`; while Flaglane is unreachable the SDK backs off
exponentially with jitter up to 30 seconds, honours a 503's `Retry-After`, and keeps the last
ruleset. `init` resolves on the first ruleset or after its timeout, whichever comes first, and never
rejects; `isOn` never throws; no timer keeps a Node process alive. **Consequences:** an application
cannot be broken by the SDK, whatever the network or its own input does; a change takes up to five
seconds to arrive until a stream exists; and an attribute named `key` cannot be targeted, since the
name is the user key. **Rejected:** a CommonJS build alongside — a second artefact to keep identical
for an ecosystem that has moved on, and easy to add later; a bundler — nothing to bundle without
dependencies; a nested `{ key, attributes }` context — what the REST API takes, but more ceremony at
every call site, and the README had promised the flat one; an `init` that rejects when Flaglane is
unreachable — the one behaviour that would make Flaglane's outage the application's.

---

## ADR-027 — Cut list item 1 invoked: no stream in v0.1, five-second polling

The roadmap's first cut, written before it was needed, takes Server-Sent Events out under time
pressure: slices 4.1 (the stream endpoint), 4.3 (the SDK's subscription) and 4.4 (stream reconnect).
Six weeks of work was estimated at 33.5 days, and the SDK, its parity suite and the demo
application — all on the never-cut list — had not been built. **Decision:** the cut is invoked for
v0.1. There is no `GET /sdk/stream`; the SDK polls `GET /sdk/config` every five seconds with the
ETag of the ruleset it holds, which ADR-004's amendment names as the accepted degradation rather
than the 30-second polling it rejects. **Consequences:** a change reaches SDKs within the poll
interval plus one request — about five seconds, not under one — so NFR-PER-003's sub-second target
does not hold and is restated (E-039), milestone 4's exit criterion becomes "within five seconds",
and the README says "polled every five seconds" where it said "live stream". A kill switch still
takes effect without a redeploy, in seconds. An unchanged ruleset costs one empty `304` per SDK per
five seconds. Nothing was removed: the stream had not been built, and the SDK has polled from its
first version, so the cut is a decision about what v0.1 promises. Streaming returns as ADR-004
designs it once v0.1 ships; the SDK's polling stays as its fallback. **Rejected:** keeping the stream
and cutting the demo application or the parity suite — both are on the never-cut list, because
they are the claims the project rests on; cutting to 30-second polling — the worst case ADR-004
calls unacceptable for a kill switch.
