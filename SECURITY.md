# Security policy

## Supported versions

Flaglane is pre-release. No version has been published yet, so only the `main` branch receives
fixes. This section will list supported release lines once v0.1.0 ships.

## Reporting a vulnerability

Report privately through GitHub's private vulnerability reporting: open the
[Security tab](https://github.com/SanduniLiyanage/flaglane/security/advisories/new) of this
repository and submit a draft advisory. Do not open a public issue.

Please include:

- What the issue is and which component it affects
- Steps to reproduce, or a proof of concept
- The version or commit you tested
- Any impact you have already established

You will get an acknowledgement within 5 working days and an assessment within 14 days. If a fix is
warranted, you will be told when it lands and credited in the advisory unless you prefer otherwise.

## Scope

In scope:

- Tenant isolation failures — any path where one project's data is reachable with another
  project's credentials
- A `client` API key returning a flag that is not marked client-side visible
- API key handling: recovery of a plaintext key, keys written to logs, ineffective revocation
- Authentication and session handling on the management API
- Injection, deserialisation and denial-of-service issues in the request path

Out of scope:

- Findings that require an attacker who already holds a valid API key for the environment they are
  reading. The full ruleset for an environment is served to any valid key by design; that is what
  in-process evaluation means.
- Dashboard sign-out not invalidating an already-issued access token. See the limitation below;
  this is a known and documented property of v0.x, not a finding.
- Bucketing being predictable. Bucket assignment is a plain MurmurHash3 of the flag key and user
  key, and is not a security boundary. See ADR-003 in [`docs/DECISIONS.md`](docs/DECISIONS.md).
- Misconfiguration of a self-hosted deployment, such as an exposed database port or a default
  credential left in place.
- Reports from automated scanners with no demonstrated impact.

## Known limitations in v0.x

Stated here because a limitation you can read is worth more than one you discover.

- **No server-side session invalidation.** Sign-in issues one JWT access token valid for 8 hours.
  There is no refresh token and no revocation list, so signing out discards the token in the
  browser and nothing more: a token already stolen stays valid until it expires. The fix is a
  persisted, hashed refresh token with a `revoked_at` column, and it is deliberately not in v0.x
  rather than half-built — a refresh token that cannot be revoked is a longer-lived credential
  wearing the word "refresh" (ADR-012). Deploy behind TLS and keep the token in memory rather
  than `localStorage`.
- **Client keys never receive user overrides.** Override user keys are real user identifiers, so
  they are omitted from client rulesets entirely rather than obscured. The consequence is that
  user overrides do not apply to client-side evaluation at all (FR-KEY-008). Target specific
  users client-side with a targeting rule on an attribute your application already sends.
- **API keys are bearer credentials with no scoping beyond environment and type.** A key reads
  everything its type entitles it to in its environment. There is no per-flag key.

## Deployment notes

Flaglane is self-hosted. You are responsible for the deployment. In particular:

- All configuration, including database credentials and JWT signing keys, comes from environment
  variables. Nothing is read from committed files.
- Serve the API over TLS. API keys are bearer credentials and are sent on every SDK request.
- `client` keys are public by design. They reach browsers, and so does every flag marked
  client-side visible. Keep backend-only flags unmarked.
