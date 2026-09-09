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
- **Flag configuration** — the per-environment state: enabled, rules, rollout, default.
- **Targeting rule** — an ordered condition on user attributes producing a value.
- **User override** — an explicit assignment for a named user key.
- **Ruleset** — the complete evaluation input for one environment.
- **Bucket** — an integer 0–99 derived from flag key and user key, stable forever.
- **API key** — a secret identifying one environment and one key type.

## 4. Functional requirements

### 4.1 Accounts and projects

- **FR-ACC-001** A user registers with email and password. Passwords are stored with bcrypt.
- **FR-ACC-002** A user signs in and receives a short-lived access token and a refresh token.
- **FR-PRJ-001** A signed-in user creates a project with a display name and a URL-safe key.
- **FR-PRJ-002** A project key is unique across the system and immutable after creation.
- **FR-PRJ-003** A user sees only projects they own. Cross-project access returns 404, not 403,
  so project existence is not disclosed.
- **FR-ENV-001** Creating a project creates `development`, `staging` and `production`
  environments automatically.
- **FR-ENV-002** A user may create additional environments with a unique key per project.
- **FR-ENV-003** An environment cannot be deleted while it holds a non-revoked API key.

### 4.2 API keys

- **FR-KEY-001** A key belongs to exactly one environment and has type `server` or `client`.
- **FR-KEY-002** The plaintext key is displayed once at creation and never again. Only a SHA-256
  hash and a short non-secret prefix are stored.
- **FR-KEY-003** A key is revocable. A revoked key is rejected immediately, including on
  already-open streams.
- **FR-KEY-004** A `server` key reads all flags in its environment.
- **FR-KEY-005** A `client` key reads only flags marked client-side visible. Browser keys are
  public by nature and must not expose backend-only flags.
- **FR-KEY-006** The last-used timestamp is recorded, at most once per minute per key.

### 4.3 Flags

- **FR-FLG-001** A flag has a key unique within its project, a name, and a description.
- **FR-FLG-002** A flag key is immutable after creation. Application code depends on it.
- **FR-FLG-003** Creating a flag creates a configuration in every environment of the project,
  disabled, defaulting to `false`.
- **FR-FLG-004** A flag is marked client-side visible or not. Default is not visible.
- **FR-FLG-005** A flag is archived, never hard-deleted, so audit history stays meaningful.
- **FR-FLG-006** Each configuration holds: `enabled`, `defaultValue`, `rolloutPercentage`,
  ordered targeting rules, and user overrides.

### 4.4 Targeting

- **FR-RUL-001** A user override maps a user key to a fixed value, unique per configuration.
- **FR-RUL-002** A targeting rule has a priority, an attribute name, an operator, a value list,
  and a result value.
- **FR-RUL-003** Supported operators: `EQUALS`, `NOT_EQUALS`, `IN`, `NOT_IN`, `CONTAINS`,
  `STARTS_WITH`, `ENDS_WITH`, `MATCHES_REGEX`.
- **FR-RUL-004** Rule priorities within a configuration are contiguous and unique. Reordering is
  a single atomic operation.
- **FR-RUL-005** A regex operator is compiled with a length limit and a match timeout, so a
  malicious pattern cannot stall evaluation.

### 4.5 Evaluation

- **FR-EVL-001** Evaluation resolves in this order, first match wins:
  1. Configuration `enabled` is false → return `defaultValue` (kill switch).
  2. A user override matches the user key → return its value.
  3. Targeting rules in priority order → first match returns its result value.
  4. `bucket(flagKey, userKey) < rolloutPercentage` → return `true`.
  5. Return `defaultValue`.
- **FR-EVL-002** `bucket` is `murmur3_32(flagKey + ":" + userKey, seed=0) % 100`, unsigned.
- **FR-EVL-003** Bucketing is deterministic: identical inputs produce identical output on every
  process, machine, restart and release, indefinitely.
- **FR-EVL-004** The flag key is part of the hash input, so two flags at the same percentage
  select different user populations.
- **FR-EVL-005** If no user key is supplied, rules and rollout are skipped and `defaultValue` is
  returned. Anonymous users get a stable, documented result.
- **FR-EVL-006** Any error during evaluation resolves to `defaultValue` and logs at WARN.
  Evaluation never propagates an exception to the caller.
- **FR-EVL-007** Evaluation of an unknown flag key returns the caller-supplied fallback.

### 4.6 Serving and streaming

- **FR-SRV-001** `GET /sdk/config` returns the complete ruleset for the key's environment, with
  an ETag. A matching `If-None-Match` returns 304.
- **FR-SRV-002** `POST /sdk/evaluate` evaluates named flags server-side for thin clients.
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

- **FR-AUD-001** Every mutation records actor, timestamp, project, environment, flag, action,
  previous value and new value, in the same transaction as the change.
- **FR-AUD-002** Audit entries are append-only. There is no update or delete path.
- **FR-AUD-003** A user views the audit trail for a flag or an environment, newest first.

### 4.9 Dashboard

- **FR-UI-001** Sign in and sign out.
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
- **NFR-PER-001** In-process SDK evaluation: p99 under 1 ms with 1,000 flags loaded.
- **NFR-PER-002** `GET /sdk/config`: p99 under 50 ms served from cache, measured locally.
- **NFR-PER-003** A dashboard change reaches connected SDKs in under 1 second at p95.
- **NFR-PER-004** The evaluation path performs zero database queries per request.

### Security
- **NFR-SEC-001** API keys stored as SHA-256 hashes. No plaintext, no reversible encryption.
- **NFR-SEC-002** A client key never returns a flag that is not client-side visible.
- **NFR-SEC-003** No API key or credential is ever written to logs, including on error paths.
- **NFR-SEC-004** Tenant isolation is enforced at the repository layer, not by convention.
- **NFR-SEC-005** The SDK evaluation API is rate limited per key.

### Reliability
- **NFR-REL-001** Service unavailability degrades applications to last-known-good, then to
  code-level defaults. It never causes an application error.
- **NFR-REL-002** The in-memory cache rebuilds from the database on startup and after any write.
- **NFR-REL-003** `/actuator/health` reports database and cache readiness.

### Maintainability
- **NFR-MNT-001** `evaluation/` has no framework dependency and 90% line coverage.
- **NFR-MNT-002** Every endpoint is documented in OpenAPI, generated from source.
- **NFR-MNT-003** Every schema change ships as a forward-only Flyway migration.

## 6. Errata

Corrections to this document, recorded rather than silently edited.

| ID | Requirement | Change | Reason |
| --- | --- | --- | --- |
| — | — | — | — |
