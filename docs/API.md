# API contract

Two surfaces with different callers, different auth and different failure behaviour. See ADR-006.
The generated OpenAPI document at `/v3/api-docs` is authoritative; this file explains intent and
shows the shapes.

## Conventions

- JSON, `application/json`, UTC ISO-8601 timestamps.
- Errors follow RFC 9457 Problem Details: `type`, `title`, `status`, `detail`, `instance`. A
  validation failure adds an `errors` member naming each invalid field. The rejected value is never
  echoed, because the field may be a password:

  ```json
  {
    "type": "about:blank", "title": "Bad Request", "status": 400,
    "detail": "The request has invalid fields",
    "errors": [{ "field": "rules[1]", "message": "unknown operator MATCHES_REGEX" }]
  }
  ```
- Cross-tenant access returns **404**, never 403 (FR-PRJ-003). The project, environment and flag a
  path names are resolved before the request body is read, so another tenant's resource is a 404
  even when the body would have been a 400.
- Every rollout is a whole percentage on this API and basis points in the ruleset (ADR-011). A
  fractional percentage is refused, not rounded.
- There is no `Idempotency-Key`. Half-specified idempotency is worse than none, because callers
  send the header and assume it does something. If key creation ever needs it — the one mutation
  where a retry could silently mint a second live credential — it arrives with a requirement, a
  store, a retention policy and a defined replay response.

## Management API — `/api/**`

Auth: `Authorization: Bearer <jwt>`, except registration and sign-in. Every mutation is audited
in the transaction that makes it (FR-AUD-001). If the database is unreachable the management API
answers 503.

### Auth
| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/auth/register` | FR-ACC-001. 201, or 409 if the email is taken |
| POST | `/api/auth/login` | FR-ACC-002. One access token, valid 8 hours. 401 for an unknown email and a wrong password alike |

```json
POST /api/auth/register
{ "email": "amara@example.com", "password": "correct-horse-battery", "displayName": "Amara" }

201
{ "id": "0f6b3b5e-…", "email": "amara@example.com", "displayName": "Amara",
  "createdAt": "2026-10-05T09:30:00.123456Z" }
```

The email is stored trimmed and lower-cased. A password is 15 characters to 72 bytes of UTF-8,
the most bcrypt reads; there are no composition rules (ADR-021). `displayName` is optional.

```json
POST /api/auth/login
{ "email": "amara@example.com", "password": "correct-horse-battery" }

200
{ "accessToken": "eyJhbGciOiJIUzI1NiJ9…", "tokenType": "Bearer",
  "expiresAt": "2026-10-05T17:30:00Z" }
```

There is no refresh token and no `/api/auth/me` (ADR-012).

### Projects and environments
| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/projects` | Caller's projects only |
| POST | `/api/projects` | Creates three default environments (FR-ENV-001). 409 if the key is taken by anyone |
| GET | `/api/projects/{projectKey}/environments` | |
| POST | `/api/projects/{projectKey}/environments` | Creates a configuration for every live flag (FR-ENV-004). 409 if the key is used in the project |
| DELETE | `/api/projects/{projectKey}/environments/{envKey}` | 204. 409 while a non-revoked key exists (FR-ENV-003) |

```json
POST /api/projects                        POST .../environments
{ "key": "storefront", "name": "Storefront" }   { "key": "qa", "name": "QA" }

201                                       201
{ "key": "storefront", "name": "Storefront",    { "key": "qa", "name": "QA",
  "createdAt": "…" }                              "createdAt": "…" }
```

Project, environment and flag keys, and rollout salts, match `^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$`
(`docs/DATABASE.md`). Keys are how every endpoint names a resource; database ids are not exposed.

Projects are not deletable in v0.x. A project owns an audit trail that outlives it, and nothing in
the product needs the operation; the alternative is a cascade that quietly destroys the record of
what was destroyed. A deleted environment's audit entries are kept and keep its id (E-038).

### Keys
| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/projects/{projectKey}/environments/{envKey}/keys` | Prefix and metadata only |
| POST | `/api/projects/{projectKey}/environments/{envKey}/keys` | **Only response containing plaintext** (FR-KEY-002) |
| DELETE | `/api/projects/{projectKey}/environments/{envKey}/keys/{keyId}` | Revoke; 204. Revoking a revoked key is accepted and changes nothing |

```json
POST .../keys
{ "name": "checkout-service", "type": "server" }

201
{ "id": "6d1c…", "name": "checkout-service", "type": "server", "prefix": "flg_srv_Xk3mP9qa",
  "createdAt": "…", "key": "flg_srv_Xk3mP9qaR2vT8wY1zB4cD6eF0gH5jK7lM9nQ2sU4xW6" }

GET .../keys
[ { "id": "6d1c…", "name": "checkout-service", "type": "server", "prefix": "flg_srv_Xk3mP9qa",
    "createdAt": "…", "lastUsedAt": "…", "revokedAt": null } ]
```

`type` is `server` or `client`. `lastUsedAt` is accurate to about a minute (FR-KEY-006). A revoked
key is refused on every SDK request from the moment the revoke returns (FR-KEY-003).

### Flags
| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/projects/{projectKey}/flags` | Live and archived, by key |
| POST | `/api/projects/{projectKey}/flags` | Creates configs in all environments (FR-FLG-003). 409 if the key is used, archived flags included |
| GET | `/api/projects/{projectKey}/flags/{flagKey}` | |
| PATCH | `/api/projects/{projectKey}/flags/{flagKey}` | Name, description, visibility. Key immutable (FR-FLG-002). 409 while archived |
| POST | `/api/projects/{projectKey}/flags/{flagKey}/archive` | FR-FLG-005. Archiving an archived flag changes nothing |
| POST | `/api/projects/{projectKey}/flags/{flagKey}/restore` | FR-FLG-007. Fills in a configuration for any environment created while archived |

```json
POST .../flags
{ "key": "new-checkout", "name": "New checkout", "description": null, "clientSideVisible": false }

201, and the body of every other flag endpoint
{ "key": "new-checkout", "name": "New checkout", "description": null,
  "clientSideVisible": false, "archivedAt": null, "createdAt": "…" }
```

`clientSideVisible` defaults to `false`: browser keys are public. In a `PATCH`, a field left out is
left unchanged and an empty `description` clears it.

### Configuration and targeting
| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/projects/{projectKey}/flags/{flagKey}/config/{envKey}` | |
| PATCH | `/api/projects/{projectKey}/flags/{flagKey}/config/{envKey}` | Kill switch, fallthrough value, rollout percentage, rollout salt. 409 while archived |
| GET | `.../config/{envKey}/rules` | In priority order |
| PUT | `.../config/{envKey}/rules` | Replaces the ordered rule list atomically (FR-RUL-004) |
| GET | `.../config/{envKey}/overrides` | |
| PUT | `.../config/{envKey}/overrides` | Replaces the override set |

```json
PATCH .../config/production
{ "enabled": true, "fallthroughValue": false, "rolloutPercentage": 30 }

200, and the body of GET
{ "flagKey": "new-checkout", "environment": "production", "enabled": true, "offValue": false,
  "fallthroughValue": false, "rolloutPercentage": 30, "rolloutSalt": "new-checkout",
  "updatedAt": "…" }
```

`PATCH .../config/{envKey}` accepts `rolloutPercentage` as an integer 0–100 and stores it as basis
points (ADR-011). It does not accept `offValue`: a disabled flag returns `false` in v0.x and that
is not configurable, because the kill switch has to be unconditional (ADR-009); an `offValue` in
the body is ignored. It accepts `rolloutSalt`, which defaults to the flag key; changing it
re-buckets every user of that flag, and the endpoint says so in its OpenAPI description. Fields
left out are left unchanged.

```json
PUT .../config/production/rules
{ "rules": [
    { "attribute": "country", "operator": "IN", "matchValues": ["LK", "IN"], "resultValue": true },
    { "attribute": "plan", "operator": "EQUALS", "matchValues": ["free"], "resultValue": false } ] }

PUT .../config/production/overrides
{ "overrides": [ { "userKey": "u-1042", "value": true } ] }
```

Both answer with the stored list in the same shape. A rule's priority is its position in the list.
Rules are replaced as a whole list rather than patched individually: partial rule edits produce
transient invalid orderings that could be served to production for milliseconds.

A rule the engine could not apply is refused with 400 naming it (FR-RUL-010), by the engine's own
definition (ADR-019): `EQUALS`, `NOT_EQUALS`, `CONTAINS`, `STARTS_WITH` and `ENDS_WITH` take exactly
one value, the last three strings only; `IN` and `NOT_IN` take one or more values of one type. A
configuration holds at most 100 rules of at most 1,000 values each, and at most 1,000 overrides
(ADR-023). Each user key may appear once.

### Audit
| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/projects/{projectKey}/audit` | Newest first, keyset paginated on `(created_at, id)`. **Not built yet** |

Audit pagination is keyset, not offset: `?before=<created_at>,<id>&limit=n`. Entries arrive while
a reader pages, and an offset against a descending-time index repeats and skips rows. Entries are
written today; reading them over the API is not yet implemented.

## Serving API — `/sdk/**`

Auth: `Authorization: Bearer <sdk key>`. Rate limited per key (NFR-SEC-005) — **not yet**, slice
4.7.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/sdk/config` | Full ruleset for the key's environment, with ETag (FR-SRV-001) |
| POST | `/sdk/evaluate` | Server-side evaluation for thin clients (FR-SRV-002) |
| GET | `/sdk/stream` | SSE change notifications (FR-SRV-003). **Not in v0.1**: cut for five-second polling (ADR-027) |

Both built endpoints answer from memory and never query the database, so they keep working while
it is down (NFR-PER-004).

`GET /sdk/config` with a `client` key returns only client-side-visible flags (FR-KEY-005), and
carries **no `overrides` array on any flag** (FR-KEY-008). This is a security boundary, not a
filter for convenience: the response reaches browsers, and override user keys are real user
identifiers. The limitation that follows — user overrides do not apply to client keys, on either
serving path — is deliberate and documented rather than worked around with a hash.

### Caching

Every `/sdk/**` response carries:

```
Cache-Control: private, no-store
Vary: Authorization
ETag: "<ruleset_version>-<key_type>"
```

The ETag includes the key type because one URL returns two different bodies at the same version —
a server key's full ruleset and a client key's filtered one. Keyed on the version alone, a client
key's ETag would validate a server key's request, and any intermediary caching on URL could hand a
browser the flags, rules and overrides that were filtered out for it. `Vary` and `no-store` close
the same hole from the other side. `If-None-Match` with a matching ETag, weak or strong, or `*`,
returns 304 with no body.

### Ruleset shape

```json
{
  "environment": "production",
  "version": 412,
  "flags": [
    {
      "key": "new-checkout",
      "enabled": true,
      "offValue": false,
      "fallthroughValue": false,
      "rolloutBasisPoints": 3000,
      "rolloutSalt": "new-checkout",
      "overrides": [{ "userKey": "u-1042", "value": true }],
      "rules": [
        { "priority": 0, "attribute": "country", "operator": "IN",
          "matchValues": ["LK"], "resultValue": true }
      ]
    }
  ]
}
```

The ruleset carries basis points, not percentages: the SDK compares `bucket < rolloutBasisPoints`
and never has to reason about units. `offValue` is always `false` in v0.x and is carried anyway, so
that making it configurable later is a value change rather than a wire-format change. Archived
flags are not served.

### `POST /sdk/evaluate`

For clients that cannot hold a ruleset. It runs the same engine server-side, against the same
ruleset the key would download, so it answers exactly as an in-process evaluation would.

```json
{
  "context": { "key": "u-1042", "attributes": { "country": "LK", "plan": "pro" } },
  "flags": [
    { "key": "new-checkout", "fallback": false },
    { "key": "spelled-wrong", "fallback": true }
  ]
}
```

```json
{
  "environment": "production",
  "version": 412,
  "results": {
    "new-checkout":   { "value": true,  "reason": "RULE_MATCH" },
    "spelled-wrong":  { "value": true,  "reason": "FLAG_NOT_FOUND" }
  }
}
```

`context` and `context.key` are optional; omitting the key skips overrides and rollout but still
evaluates rules (FR-EVL-005). Attributes that are not a string, number or boolean are ignored
(FR-RUL-006). `fallback` is required per flag — it is the value the caller has decided is safe,
and it is what an unknown flag, an unreadable flag or an internal error resolves to. At most 100
flags per request.

`reason` is one of `OFF`, `OVERRIDE`, `RULE_MATCH`, `ROLLOUT`, `FALLTHROUGH`, `FLAG_NOT_FOUND`,
`ERROR`. An unknown flag is **200 with the caller's fallback**, never 404 (FR-EVL-007): a thin
client must not get an HTTP error where a thick client gets its fallback. A flag that exists but
is not readable by this key reports `FLAG_NOT_FOUND` too, so the response does not disclose it.

### Polling, and the stream that is not in v0.1

In v0.1 an SDK learns of a change by polling: every five seconds it sends `GET /sdk/config` with the
ETag it holds as `If-None-Match`, and gets an empty 304 until the ruleset changes. Streaming was the
roadmap's first cut (ADR-027). When it is built, it is as follows, and polling stays as its fallback:

```
event: ruleset-changed
data: {"environment":"production","version":413}
```

The event carries a version, not the ruleset. The SDK refetches, so one code path builds a
ruleset instead of two, and a missed event self-corrects on the next poll.

A heartbeat comment goes out every 30 seconds (FR-STR-002); idle proxies close silent connections.

### Error behaviour

The serving API is on the customer's critical path. Any 5xx must be safe to ignore — the SDK
falls back to its last known ruleset. Client errors are still precise, because a wrong key should
be diagnosable:

| Status | Meaning |
| --- | --- |
| 401 | Missing, malformed, or revoked key. One answer for all three |
| 400 | Malformed request body on `POST /sdk/evaluate`, or a flag without a `fallback` |
| 429 | Rate limit exceeded, with `Retry-After` (slice 4.7) |
| 503 | Ruleset not loaded for the key's environment yet, with `Retry-After: 5`; SDK retries with backoff |

There is deliberately no 404 for an unknown flag. 404 on `/sdk/**` means a path that does not
exist, nothing more.
