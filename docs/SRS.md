# Software Requirements Specification

Version 1.0. Requirement IDs are stable and referenced from commits, tests and code comments.
When a requirement changes, add an erratum at the bottom rather than editing history silently.

## 1. Scope

Flaglane is a self-hostable feature flag and remote configuration service. It stores flag rules,
serves them to client applications through SDKs, and provides a dashboard for changing them at
runtime.

Out of scope for v0.x: experimentation statistics, multivariate flags, RBAC, SSO, approval
workflows, scheduled changes, SDKs other than TypeScript.

## 2. Actors

| Actor | Description |
| --- | --- |
| Dashboard user | A developer who signs in to create and change flags |
| SDK client | An application process holding an API key |
| System | Background work: cache rebuild, stream broadcast |

## 3. Domain glossary

- **Project** — a product or application. Owns flags.
- **Environment** — a deployment target within a project (`development`, `staging`, `production`).
- **Flag** — a named switch, unique within a project. Has one configuration per environment.
- **Flag configuration** — the per-environment state: enabled, rules, rollout, off value,
  fallthrough value.
- **Targeting rule** — an ordered condition on user attributes producing a value.
- **User override** — an explicit assignment for a named user key.
- **Ruleset** — the complete evaluation input for one environment.
- **Off value** — the value returned when a configuration is disabled. Fixed `false` in v0.x.
- **Fallthrough value** — the value returned when a configuration is enabled and nothing else
  matched.
- **Bucket** — an integer 0–9999 derived from the rollout salt and the user key, stable forever.
- **Basis point** — one hundredth of one percent. Rollout is stored in basis points, 0–10000.
- **Rollout salt** — the string mixed with the user key to produce the bucket. Defaults to the
  flag key.
- **API key** — a secret identifying one environment and one key type.

## 4. Functional requirements

### 4.1 Accounts and projects

- **FR-ACC-001** A user registers with email and password. Passwords are stored with bcrypt.
- **FR-ACC-002** A user signs in and receives a single access token, a JWT valid for 8 hours.
  There is no refresh token in v0.x, and therefore no `POST /api/auth/refresh`. A refresh token
  needs either a table to revoke against or an accepted window in which a stolen token outlives
  sign-out, and v0.x is not going to pretend it has the first.
- **FR-PRJ-001** A signed-in user creates a project with a display name and a URL-safe key.
- **FR-PRJ-002** A project key is unique across the system and immutable after creation. Both
  halves are enforced by the database: a unique constraint and a before-update trigger.
- **FR-PRJ-003** A user sees only projects they own. Cross-project access returns 404, not 403,
  so project existence is not disclosed.
- **FR-ENV-001** Creating a project creates `development`, `staging` and `production`
  environments automatically.
- **FR-ENV-002** A user may create additional environments with a unique key per project.
- **FR-ENV-003** An environment is deleted through `DELETE .../environments/{envKey}`, and cannot
  be deleted while it holds a non-revoked API key. Deleting one cascades to its flag
  configurations, rules and overrides, and blanks the environment reference on its audit entries
  rather than removing them.
- **FR-ENV-004** Creating an environment creates a configuration for every non-archived flag in
  the project, in the same transaction — disabled, `fallthroughValue` `false`, rollout 0. Without
  this a new environment serves an empty ruleset, every flag falls through to the SDK's code-level
  fallback, and the result looks exactly like a working system.

### 4.2 API keys

- **FR-KEY-001** A key belongs to exactly one environment and has type `server` or `client`.
- **FR-KEY-002** The plaintext key is displayed once at creation and never again. Only a SHA-256
  hash and a short non-secret prefix are stored. A key is 32 bytes from a CSPRNG, base64url
  encoded, behind a type marker: `flg_srv_…` or `flg_cli_…` (format in `docs/DATABASE.md`). The
  256 bits of entropy are what make a fast hash the right choice rather than bcrypt (ADR-007);
  the marker is for humans and secret scanners and is never trusted for authorisation.
- **FR-KEY-003** A key is revocable. A revoked key is rejected immediately, including on
  already-open streams.
- **FR-KEY-004** A `server` key reads all flags in its environment.
- **FR-KEY-005** A `client` key reads only flags marked client-side visible. Browser keys are
  public by nature and must not expose backend-only flags.
- **FR-KEY-006** The last-used timestamp is recorded, at most once per minute per key, and is
  written asynchronously off the request path. A serving request never waits on a write.
- **FR-KEY-007** Key authentication is served from an in-memory cache of key hashes, built at
  startup and invalidated on issue and on revoke. A `/sdk/**` request performs no database query,
  which is what makes "the database is down and serving continues" true. FR-KEY-003's "rejected
  immediately" is a property of the invalidation, not of a per-request lookup.
- **FR-KEY-008** A ruleset served to a `client` key contains no user overrides at all, for any
  flag. `user_overrides.user_key` holds real user identifiers, and a ruleset that ships them puts
  the list of people you have been targeting into every browser that loads the page.

  The consequence is a real limitation, stated rather than hidden: **user overrides do not apply
  to client keys**, on either serving path. `POST /sdk/evaluate` with a client key ignores them
  too, so both paths give the same answer for the same key and the parity suite can assert it.
  Targeting a specific user client-side is done with a targeting rule on an attribute the
  application already sends.

### 4.3 Flags

- **FR-FLG-001** A flag has a key unique within its project, a name, and a description.
- **FR-FLG-002** A flag key is immutable after creation, enforced by a before-update trigger.
  Application code depends on it.
- **FR-FLG-003** Creating a flag creates a configuration in every environment of the project:
  disabled, `fallthroughValue` `false`, rollout 0, no rules and no overrides.
- **FR-FLG-004** A flag is marked client-side visible or not. Default is not visible.
- **FR-FLG-005** A flag is archived, never hard-deleted, so audit history stays meaningful. An
  archived flag keeps its key reserved: the key cannot be reused for a new flag, because a key
  that comes back with a different meaning makes the audit trail lie.
- **FR-FLG-006** Each configuration holds: `enabled`, `offValue`, `fallthroughValue`,
  `rolloutBasisPoints`, `rolloutSalt`, ordered targeting rules, and user overrides.
- **FR-FLG-007** An archived flag is restorable. Restoring changes neither its key nor its
  configurations. Archiving with no way back is a trap for a mis-click.

### 4.4 Targeting

- **FR-RUL-001** A user override maps a user key to a fixed value, unique per configuration.
- **FR-RUL-002** A targeting rule has a priority, an attribute name, an operator, a value list,
  and a result value.
- **FR-RUL-003** Supported operators: `EQUALS`, `NOT_EQUALS`, `IN`, `NOT_IN`, `CONTAINS`,
  `STARTS_WITH`, `ENDS_WITH`. There is no regular-expression operator in v0.x.
- **FR-RUL-004** Rule priorities within a configuration are unique, enforced by a non-deferrable
  unique constraint, and contiguous from 0, assigned by the service and asserted by a repository
  test. Contiguity is not expressible as a declarative constraint and is not claimed to be.
  Reordering replaces the whole list in one transaction, so no intermediate state is ever visible
  or ever holds a duplicate priority.
- **FR-RUL-005** *Withdrawn* (E-010). It required a regex match timeout, which neither runtime can
  provide. The ID is retired and is never reused.

#### Comparison semantics

The server and the SDK are two implementations of one specification. Every sentence left unwritten
here is a place they will disagree, silently and in production (E-011).

- **FR-RUL-006** A user context attribute value is a string, a number, or a boolean. Arrays,
  objects and null are rejected by the management API and ignored by the engine.
- **FR-RUL-007** Comparison is exact and type-strict. There is no coercion: the number `1` never
  equals the string `"1"`. String comparison is case-sensitive, byte-for-byte over the UTF-8
  encoding, with no Unicode normalisation and no locale involvement.
- **FR-RUL-008** An attribute absent from the user context never matches, for every operator
  including the negative ones. `country NOT_EQUALS "US"` does not match a context carrying no
  `country`. Absence is not null and is not a value.
- **FR-RUL-009** `CONTAINS`, `STARTS_WITH` and `ENDS_WITH` apply to strings only; a non-string on
  either side never matches. `IN` and `NOT_IN` compare against a list whose entries are all of one
  type.
- **FR-RUL-010** Rules are validated when written, not when evaluated. An unknown operator, an
  empty value list, or a value of a type the operator cannot accept is rejected with 400, so a
  malformed rule cannot reach the serving path in the first place.

### 4.5 Evaluation

- **FR-EVL-001** Evaluation resolves in this order, first match wins:
  1. Configuration `enabled` is false → return `offValue` (kill switch).
  2. A user override matches the user key → return its value.
  3. Targeting rules in priority order → first match returns its result value.
  4. `bucket(rolloutSalt, userKey) < rolloutBasisPoints` → return `true`.
  5. Return `fallthroughValue`.

  `offValue` is fixed `false` in v0.x and is not editable, so disabling a flag can only turn a
  feature off. `fallthroughValue` is the editable one. When `fallthroughValue` is `true` the
  rollout has no effect, because step 5 then returns `true` to everyone step 4 did not reach; the
  dashboard says so rather than leaving it to be discovered.
- **FR-EVL-002** `bucket` is computed as follows. Every clause is load-bearing for server/SDK
  parity, and the ones that look pedantic are the ones that diverge in production:
  - Input string: `rolloutSalt + ":" + userKey`.
  - Encoding: UTF-8 bytes. Not UTF-16, not a platform default.
  - Algorithm: MurmurHash3 **x86_32**, `seed = 0`. Not x64_128 truncated to 32 bits.
  - Unsigned conversion before the modulo: `Integer.toUnsignedLong(h) % 10000` in Java,
    `(h >>> 0) % 10000` in TypeScript. Left signed, Java yields a negative bucket where
    JavaScript yields a positive one for the same input.
  - Result: an integer 0–9999.

  Project, environment and flag keys and the rollout salt are constrained to a character set that
  excludes `:` (`docs/DATABASE.md`), so the separator cannot collide.
- **FR-EVL-003** Bucketing is deterministic: identical inputs produce identical output on every
  process, machine, restart and release, indefinitely.
- **FR-EVL-004** The rollout salt participates in the hash and defaults to the flag key, so two
  flags at the same percentage select different user populations — unless they are deliberately
  given the same salt in order to roll out to the same cohort together.
- **FR-EVL-005** If no user key is supplied, user overrides and the percentage rollout are
  skipped, because neither can produce a stable answer without one. Targeting rules are still
  evaluated against whatever attributes were supplied; with no rule matching, `fallthroughValue`
  is returned. Attribute targeting therefore works for anonymous users, which is most of what
  client-side flags are for.
- **FR-EVL-006** Any error during evaluation of a known flag resolves to `fallthroughValue` and
  logs at WARN. Evaluation never propagates an exception to the caller. The warning is rate
  limited to one per flag key per minute per process; the alternative is an unbounded log write on
  the hot path.
- **FR-EVL-007** Evaluation of an unknown flag key returns the caller-supplied fallback, on every
  path. `POST /sdk/evaluate` returns 200 with that fallback and `"reason": "FLAG_NOT_FOUND"`. It
  never returns 404 for an unknown flag.
- **FR-EVL-008** Increasing `rolloutBasisPoints` is monotone: no user already receiving the flag
  through the rollout loses it. This is the property that makes `bucket <` the correct comparison
  and "increase the rollout" a safe operation rather than a reshuffle. Asserted by suite 11.

### 4.6 Serving and streaming

- **FR-SRV-001** `GET /sdk/config` returns the ruleset the key is entitled to, with an ETag. A
  matching `If-None-Match` returns 304. The ETag is derived from
  `(environments.ruleset_version, key_type)`, never from the version alone: one URL serves two
  different bodies at the same version depending on key type. Every `/sdk/**` response carries
  `Cache-Control: private, no-store` and `Vary: Authorization`, so no intermediary can serve a
  server key's ruleset to a browser holding a client key.
- **FR-SRV-002** `POST /sdk/evaluate` evaluates named flags server-side for thin clients, running
  the same engine as the SDK. The caller supplies a user context and a fallback per flag. The
  response carries, per flag, the resolved value and a `reason` drawn from `OFF`, `OVERRIDE`,
  `RULE_MATCH`, `ROLLOUT`, `FALLTHROUGH`, `FLAG_NOT_FOUND` and `ERROR`. A flag the key is not
  entitled to read is reported as `FLAG_NOT_FOUND`, so the response does not disclose that it
  exists. Request and response schemas are in `docs/API.md`.
- **FR-SRV-003** `GET /sdk/stream` opens a Server-Sent Events connection.
- **FR-STR-001** Any change to a flag configuration in an environment publishes a change event to
  every open stream for that environment, and to no other environment.
- **FR-STR-002** The stream sends a heartbeat comment at least every 30 seconds so proxies do not
  close idle connections.
- **FR-STR-003** Revoking a key closes its open streams.

### 4.7 SDK

- **FR-SDK-001** `init()` fetches the ruleset, then resolves. It exposes a timeout after which it
  resolves anyway in fallback mode rather than blocking application startup.
- **FR-SDK-002** `isOn(flagKey, context, fallback)` evaluates in process with no network call.
- **FR-SDK-003** The SDK subscribes to the stream and swaps in a new ruleset atomically.
- **FR-SDK-004** If the stream drops, the SDK reconnects with exponential backoff and jitter, and
  polls `GET /sdk/config` meanwhile.
- **FR-SDK-005** If the service is unreachable, the SDK serves the last known ruleset. With no
  ruleset ever fetched, it returns the caller-supplied fallback.
- **FR-SDK-006** The SDK never throws from `isOn()`.
- **FR-SDK-007** The SDK implements the same evaluation order as the server, verified by a shared
  fixture suite (see `docs/TESTING.md`).

### 4.8 Audit

- **FR-AUD-001** Every mutation records actor, timestamp, project, action, previous value and new
  value, in the same transaction as the change. Environment and flag are recorded where the event
  has them and are null where it does not: project creation has neither, key creation has no flag.
  The action is drawn from a fixed vocabulary (`docs/DATABASE.md`).
- **FR-AUD-002** Audit entries are append-only, enforced by a before-update-or-delete trigger and
  by grants revoked from the application role. The trigger is what makes this true regardless of
  role, ownership or superuser status; the revoke alone is a speed bump.
- **FR-AUD-003** A user views the audit trail for a flag or an environment, newest first, paginated
  by keyset on `(created_at desc, id desc)`. Not by offset: entries arrive while a user is paging,
  and offset pagination against a descending-time index silently repeats and skips rows.

### 4.9 Dashboard

- **FR-UI-001** Sign in, and sign out by discarding the token in the browser. v0.x has no
  server-side session invalidation, so a token that has already been stolen stays valid until it
  expires (FR-ACC-002).
- **FR-UI-002** Project list, project creation, environment switcher.
- **FR-UI-003** Flag list per environment showing key, name, enabled state and rollout.
- **FR-UI-004** Flag detail: kill switch, default value, rollout slider, rule editor with
  reordering, user override list.
- **FR-UI-005** API key management: create, display once with a copy control, revoke.
- **FR-UI-006** Audit trail view.
- **FR-UI-007** Changes save explicitly, not on every keystroke, so a partial rule never reaches
  production.

## 5. Non-functional requirements

### Performance
- **NFR-PER-001** In-process evaluation: p99 under 25 µs with 1,000 flags loaded and 50 rules on
  the flag under evaluation. A one-millisecond budget is unfailable — a hash and a map lookup
  cannot approach it — and a measurement that cannot fail is not evidence.
- **NFR-PER-002** `GET /sdk/config`: p99 under 50 ms served from cache, measured locally.
- **NFR-PER-003** A dashboard change reaches connected SDKs in under 1 second at p95.
- **NFR-PER-004** No `/sdk/**` request performs a database query on the request path. The engine
  is a pure function over an in-memory ruleset; key authentication is served from the in-memory key
  cache (FR-KEY-007); `last_used_at` is flushed asynchronously (FR-KEY-006). "Evaluation path"
  means the whole served request, not just the engine — the engine alone was never the part that
  touched the database.

### Security
- **NFR-SEC-001** API keys stored as SHA-256 hashes. No plaintext, no reversible encryption.
- **NFR-SEC-002** A client key never returns a flag that is not client-side visible, and never
  returns a user key from any override, visible flag or not (FR-KEY-008).
- **NFR-SEC-003** No API key or credential is ever written to logs, including on error paths.
- **NFR-SEC-004** Tenant isolation is enforced at the repository layer, not by convention.
- **NFR-SEC-005** `GET /sdk/config` and `POST /sdk/evaluate` are rate limited per key by an
  in-memory token bucket: 600 requests per minute per key by default, configurable, answering 429
  with `Retry-After`. `GET /sdk/stream` is counted once when the connection is established and
  never per event — a stream is one request that lasts hours, and a per-request limiter would
  either close it or be meaningless. Buckets are per instance, which is exact while Flaglane runs
  as a single instance and is a limitation to revisit with the multi-instance work.

### Reliability
- **NFR-REL-001** Service unavailability degrades applications to last-known-good, then to
  code-level defaults. It never causes an application error.
- **NFR-REL-002** The in-memory cache rebuilds from the database on startup and after any write.
- **NFR-REL-003** Liveness (`/actuator/health/liveness`) ignores the database entirely. Readiness
  (`/actuator/health/readiness`) requires the ruleset cache only. Database health is reported as a
  separate indicator on `/actuator/health` and gates nothing. A database outage that made the
  instance report unhealthy would have an orchestrator remove or restart it — taking the serving
  path down at exactly the moment NFR-REL-001 exists to survive.

### Maintainability
- **NFR-MNT-001** `evaluation/` has no framework dependency and 90% line coverage.
- **NFR-MNT-002** Every endpoint is documented in OpenAPI, generated from source.
- **NFR-MNT-003** Every schema change ships as a forward-only Flyway migration.

## 6. Errata

Corrections to this document, recorded rather than silently edited.

| ID | Requirement | Change | Evidence |
| --- | --- | --- | --- |
| E-001 | FR-EVL-001 | `defaultValue` split into a fixed `offValue` (step 1) and an editable `fallthroughValue` (step 5). | `defaultValue` was editable and served as both the disabled value and the fallthrough value, so setting it `true` made the kill switch turn a feature **on** for everyone, including users the rules were excluding. |
| E-002 | FR-EVL-002 | Names the variant (MurmurHash3 x86_32), the encoding (UTF-8), the unsigned conversion, and changes the modulus from 100 to 10000. | Variant, encoding and signedness were all unspecified. Java's signed `int` and JavaScript's `>>>` give different buckets for the same input, and Guava's deprecated `murmur3_32()` differs from `murmur3_32_fixed()` on non-ASCII keys. The ASCII-only fixtures would have passed while production diverged. |
| E-003 | FR-EVL-004 | The hash input is a `rolloutSalt` defaulting to the flag key, not the flag key itself. | Hardcoding the flag key makes coordinated rollout across several flags inexpressible, and it cannot be retrofitted: changing the hash input reshuffles every user on every flag, the exact flicker FR-EVL-003 exists to prevent. |
| E-004 | FR-EVL-005 | Anonymous evaluation now skips overrides and rollout only. Targeting rules are still evaluated. | Rules match on attributes and are not tied to the user key, so `{country: "LK"}` with no user key could not match a `country IN ["LK"]` rule for no reason other than the requirement's wording. |
| E-005 | FR-EVL-006 | Errors resolve to `fallthroughValue`, and the warning is rate limited to one per flag key per minute. | Follows E-001. An unrate-limited warning on the evaluation path is an unbounded log write per request. |
| E-006 | FR-EVL-007 | States explicitly that `POST /sdk/evaluate` returns 200 with the caller's fallback and a `reason`, never 404. | `docs/API.md` specified 404 for an unknown flag while FR-EVL-007 specified the fallback, so a thin client got an HTTP error where a thick client got its fallback from the same specification. |
| E-007 | FR-EVL-008 | Added: increasing the rollout is monotone. | `bucket <` gives the property for free, and it is what makes raising a rollout safe, but nothing stated or tested it, so a later change to `bucket <=` or a re-salting could have removed it unnoticed. |
| E-008 | FR-FLG-006, FR-FLG-003 | Field list is now `enabled`, `offValue`, `fallthroughValue`, `rolloutBasisPoints`, `rolloutSalt`, rules, overrides, and the configuration created alongside a flag is described in those terms. | Follows E-001, E-003 and the move to basis points. `defaultValue` no longer exists, so a requirement saying a new configuration "defaults to false" no longer names anything. |
| E-009 | FR-RUL-003 | `MATCHES_REGEX` removed. Seven operators remain. | `java.util.regex.Matcher` cannot be given a timeout and `RegExp` cannot be interrupted on the main thread, so a catastrophically backtracking pattern freezes the customer's request thread or browser tab — the one failure the availability promise cannot survive. |
| E-010 | FR-RUL-005 | Withdrawn; the ID is retired and never reused. | Same as E-009: the requirement described something neither runtime can implement. |
| E-011 | FR-RUL-006 to FR-RUL-010 | Added: attribute types, type-strict and case-sensitive comparison, absent attributes never match, string operators are string-only, rules validated at write time. | Missing-attribute handling, case sensitivity and coercion were unspecified, and Java and JavaScript default to opposite answers on all three. This is one requirement implemented two incompatible ways, and the parity suite could not have expressed the divergence. |
| E-012 | FR-SRV-002 | Specifies the request and response shape, the `reason` vocabulary, and that an unreadable flag reports `FLAG_NOT_FOUND`. | The endpoint had no specified request or response shape anywhere, so there was no defined way for a caller to supply the fallback FR-EVL-007 requires. |
| E-013 | NFR-PER-001 | Budget tightened from p99 under 1 ms to p99 under 25 µs, with a stated rule count. | A hash and a map lookup cannot approach a millisecond, so the requirement was satisfied by construction and its measurement proved nothing. |
| E-014 | FR-PRJ-002, FR-FLG-002 | Immutability is enforced by a before-update trigger, stated alongside the unique constraint. | `docs/DATABASE.md` mapped `projects.key` unique to FR-PRJ-002 and claimed constraints exist "so that a bug in service code cannot produce invalid data". A unique constraint does not do immutability, and nothing covered FR-FLG-002 at all. |
| E-015 | FR-ENV-003 | Names the deletion endpoint and the cascade behaviour. | The requirement said when an environment cannot be deleted; `docs/API.md` exposed no way to delete one, so the requirement governed an operation that did not exist. |
| E-016 | FR-ENV-004 | Added: creating an environment creates a configuration for every existing flag, in the same transaction. | FR-FLG-003 creates configurations when a *flag* is created and FR-ENV-002 allows creating environments later, so a new environment served an empty ruleset — every flag falling through to code-level fallbacks, silently, looking exactly like a working system. |
| E-017 | FR-FLG-005 | States that an archived flag's key stays reserved. | `flags (project_id, key)` unique applies to archived rows, so a key can never be reused. Defensible, but it was left for the dashboard to discover at runtime. |
| E-018 | FR-FLG-007 | Added: an archived flag can be restored. | FR-FLG-005 archived flags and nothing restored them, making a mis-click permanent. |
| E-019 | FR-RUL-004 | Uniqueness is a non-deferrable database constraint; contiguity is service-enforced and asserted by a repository test, not claimed as a constraint. | Contiguity is not expressible declaratively in PostgreSQL. The deferrable constraint was also the wrong tool: it disables `ON CONFLICT` on the table permanently and moves violations to commit time, outside the `@Transactional` method, and the API replaces rule lists wholesale so no intermediate duplicate priority ever exists. |
| E-020 | FR-KEY-006 | The last-used write is asynchronous and off the request path. | FR-KEY-006 put a throttled database *write* on the serving path, contradicting NFR-PER-004 and "database down, serving continues". |
| E-021 | FR-KEY-007 | Added: key authentication is served from an in-memory cache invalidated on issue and revoke. | `docs/DATABASE.md` listed `api_keys (key_hash)` as "authentication on every SDK request" and eleven lines later said the evaluation path never touches the database. Both could not hold, and with a per-request lookup a database outage takes serving down at 401. |
| E-022 | FR-SRV-001 | ETag is derived from `(ruleset_version, key_type)`; responses carry `Cache-Control: private, no-store` and `Vary: Authorization`. | There was no environment-level version column anywhere for the ETag or the SSE event to come from. Worse, one URL returns a full ruleset to a server key and a filtered one to a client key, so an ETag keyed on version alone — with no `Vary` — lets an intermediary serve backend-only flags, rules and overrides to a browser. |
| E-023 | FR-AUD-001 | Environment and flag are recorded where the event has them and are null where it does not; the action vocabulary is fixed. | "Every mutation records ... environment, flag" is impossible for project creation, which has neither, and key creation, which has no flag, while the schema showed both as non-null foreign keys. |
| E-024 | FR-AUD-002 | Enforced by a before-update-or-delete trigger as well as revoked grants, with a separate application role. | A revoke does not hold when Flyway and the application share a role, leaves `TRUNCATE` and `DROP` available, and is bypassed entirely by Testcontainers' default superuser — so suite 10 could not have passed as written. |
| E-025 | FR-AUD-003 | Pagination is keyset on `(created_at desc, id desc)`. | Pagination was unspecified; offset against a descending-time index repeats and skips rows while new entries arrive. |
| E-026 | NFR-PER-004 | Defines the evaluation path as the whole served `/sdk/**` request, and states how authentication and `last_used_at` stay off it. | "Zero database queries per request" was contradicted by key authentication and the last-used write, both of which happen on every serving request. |
| E-027 | NFR-REL-003 | Liveness ignores the database; readiness requires the ruleset cache only; database health gates nothing. | A single health endpoint reporting the database means an outage has the orchestrator evict or restart the instance, taking the serving path down at precisely the moment NFR-REL-001 exists to survive. |
| E-028 | FR-ACC-002 | Sign-in returns one 8-hour access token. The refresh token and `POST /api/auth/refresh` are removed from v0.x. | No refresh token table existed and nothing stated whether the token was stateless or persisted. Stateless, sign-out clears the browser while the refresh token stays valid to expiry, so the one action a user takes when they suspect compromise does nothing. |
| E-029 | FR-UI-001 | Sign-out is discarding the token client-side, and says so. | Follows E-028. FR-UI-001 required sign-out that the rest of the specification could not deliver. |
| E-030 | FR-KEY-002 | Specifies key entropy (32 bytes from a CSPRNG), encoding and the `flg_srv_` / `flg_cli_` format. | ADR-007's choice of SHA-256 over bcrypt is correct only because keys are high-entropy random values, and nothing said they were. `api_keys.key_prefix` also existed as a column with no defined format behind it. |
| E-031 | FR-KEY-008 | Added: client rulesets carry no user overrides, and overrides do not apply to client keys on either serving path. | `user_overrides.user_key` holds real user identifiers and the documented ruleset shape shipped them verbatim. FR-KEY-005 filtered by flag only, so marking one flag client-visible published the list of specific users being targeted to every browser. Suite 6 asserted only that hidden *flags* were absent. |
| E-032 | NFR-SEC-002 | Extended to cover override user keys, not just flag visibility. | Same as E-031: the requirement constrained which flags appear, not which identifiers. |
| E-033 | NFR-SEC-005 | Specifies the algorithm, the default limit, the 429 behaviour, and that `/sdk/stream` is counted at connection rather than per event. | The requirement named no algorithm, limit, storage or dependency, yet a roadmap slice scheduled it. It also interacts badly with a stream, which is one request lasting hours. |
