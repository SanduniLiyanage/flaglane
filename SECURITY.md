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
- Bucketing being predictable. Bucket assignment is a plain MurmurHash3 of the flag key and user
  key, and is not a security boundary. See ADR-003 in [`docs/DECISIONS.md`](docs/DECISIONS.md).
- Misconfiguration of a self-hosted deployment, such as an exposed database port or a default
  credential left in place.
- Reports from automated scanners with no demonstrated impact.

## Deployment notes

Flaglane is self-hosted. You are responsible for the deployment. In particular:

- All configuration, including database credentials and JWT signing keys, comes from environment
  variables. Nothing is read from committed files.
- Serve the API over TLS. API keys are bearer credentials and are sent on every SDK request.
- `client` keys are public by design. They reach browsers, and so does every flag marked
  client-side visible. Keep backend-only flags unmarked.
