# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `GET /sdk/stream`: Server-Sent Events that tell an SDK, within moments of a change, which version
  of its environment's ruleset to download. The first event is the version served when the stream
  opened; a comment every 25 seconds keeps it open through idle proxies; streams last 30 minutes,
  end when their key is revoked, and are limited to 500 a key and 1,000 an environment
  (`FLAGLANE_STREAM_MAX_PER_KEY`, `FLAGLANE_STREAM_MAX_PER_ENVIRONMENT`).
- The SDK listens on the stream and downloads a changed ruleset the moment it is announced. While
  its stream is open it does not poll; whenever there is none, as against a 0.1 server, it polls
  every five seconds as before. `streaming: false` polls only; `client.streaming` says whether a
  stream is open.
- The demo shop shows whether its SDK streams, and `FLAGLANE_STREAMING=false` starts it polling.
  CI checks on every push that a change reaches it within a second over its stream, and within the
  poll interval without one.

### Changed

- In Node, an open stream keeps the process running, as any open connection does. A short-lived
  script that creates a client should call `close()` when it is done, or pass `streaming: false`.

## [0.1.0] - 2026-11-09

The first release.

- Image: `ghcr.io/sanduniliyanage/flaglane:0.1.0`, for `linux/amd64` and `linux/arm64`
- SDK: `npm install @flaglane/sdk@0.1.0`

### Added

#### Flags and evaluation

- Boolean flags per project, with a configuration in every environment: a kill switch, a
  fallthrough value, a percentage rollout, a rollout salt, ordered targeting rules and user
  overrides. Flags are archived and restored, never deleted, and their keys are never reused.
- An evaluation engine in plain Java with one resolution order: kill switch, override, targeting
  rules in order, rollout, fallthrough. A disabled flag always returns `false`, whatever else is
  configured.
- Evaluation never throws. A malformed rule resolves the flag to its fallthrough value and an
  unknown flag to the caller's fallback, with warnings limited to one per flag a minute.
- Seven targeting operators — `EQUALS`, `NOT_EQUALS`, `IN`, `NOT_IN`, `CONTAINS`, `STARTS_WITH`,
  `ENDS_WITH` — compared type-strictly and case-sensitively; an attribute the context lacks never
  matches, for any operator. Rules are validated when they are written.
- Bucketing by MurmurHash3 x86_32 over UTF-8 into 10,000 buckets, so a user's assignment is the
  same on every process and every release, and raising a rollout never takes a flag from anyone
  who has it. Flags sharing a salt roll out to the same users.

#### Serving

- `GET /sdk/config`: an environment's whole ruleset, served from memory, with an ETag per version
  and key type, `304` when nothing changed, and gzipped once when it is built rather than per
  request.
- `POST /sdk/evaluate`, for clients that cannot hold a ruleset: the same engine on the server, with
  a reason per flag, and the caller's fallback for an unknown flag rather than a `404`.
- Server and client API keys, shown once at creation, stored as SHA-256, revocable with effect from
  the moment the revoke returns. A client key reads client-side-visible flags only and never
  receives a user override, since override keys identify real users.
- The serving API needs no database to answer: keys are authenticated from memory, so it keeps
  serving while PostgreSQL is down. Readiness depends on the rulesets being loaded and on nothing
  else.
- A rate limit per key, 600 requests a minute by default, with a `304` costing a tenth of a
  request, so one key serves 500 SDKs polling every five seconds.

#### Management API and dashboard

- Registration and sign-in, with one 8-hour access token, limited to 10 attempts a minute per
  client address.
- Projects created with `development`, `staging` and `production` environments; environments can
  be added and deleted.
- Tenant isolation at the repository layer: another account's project, environment, flag or key
  is a `404`, never a `403`.
- An append-only audit trail of every change, written in the same transaction as the change and
  enforced by database triggers, read newest first with keyset pagination.
- A dashboard served by the API from its own origin: projects, environments, flags, the kill switch,
  the rollout, the rule editor, overrides, API keys and the audit trail. Edits are staged and saved
  explicitly; the kill switch saves at once.
- An OpenAPI document generated from the source, with Swagger UI.

#### SDK

- `@flaglane/sdk` on npm, for TypeScript, with no runtime dependencies, for browsers and Node 20 or
  later.
  It evaluates in process and polls for changes every five seconds with the ruleset's ETag.
- `init` never rejects and `isOn` never throws. While Flaglane is unreachable the SDK keeps its last
  ruleset and backs off with jitter, honouring `Retry-After`; started without one, it returns the
  fallback in your code.
- Shared fixtures that the server and the SDK both run, so the two engines agree, on every bucket
  of 100,000 keys included.

#### Running it

- A container image of the API and the dashboard, published to GitHub's container registry for
  x86-64 and Arm by each release tag, with a provenance attestation and an SBOM.
- A Docker Compose stack: PostgreSQL 16 with separate migration and application roles, and the API,
  whose healthcheck probes readiness.
- `docs/DEPLOYMENT.md`: two database roles on a managed PostgreSQL, TLS to the database, the
  environment variables, health checks and upgrades. Behind a reverse proxy,
  `FLAGLANE_FORWARDED_HEADERS=native` reads the client's address and scheme from the proxy's
  headers, believed only from a private address.
- `examples/demo-shop`, a storefront on the SDK. CI checks on every push that a change reaches it
  within five seconds and that it keeps working with the API stopped.
- Benchmarks, with method and hardware, in `docs/BENCHMARKS.md`: in-process evaluation with 1,000
  flags and 50 rules on the flag takes about 1 µs at p99 on the server and about 3 µs in the SDK.

### Known limitations

Each is a decision recorded in `docs/DECISIONS.md`, not an oversight.

- **One API instance.** Rulesets, keys and rate limits are held in each instance's memory, and
  nothing tells one instance of a change made through another (ADR-036).
- **No streaming.** SDKs poll every five seconds, so a change takes up to five seconds to arrive
  (ADR-027).
- **No server-side sign-out.** A dashboard token stays valid until it expires (ADR-012).
- **Boolean flags only**, and no regular-expression operator (E-009).
- **User overrides do not apply to client keys** (FR-KEY-008).
- **No hosted instance.** The project runs none; you run your own from the image (ADR-038).

[Unreleased]: https://github.com/SanduniLiyanage/flaglane/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/SanduniLiyanage/flaglane/releases/tag/v0.1.0
