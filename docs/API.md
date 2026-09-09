# API contract

Two surfaces with different callers, different auth and different failure behaviour. See ADR-006.
The generated OpenAPI document at `/v3/api-docs` is authoritative; this file explains intent.

## Conventions

- JSON, `application/json`, UTC ISO-8601 timestamps.
- Errors follow RFC 9457 Problem Details: `type`, `title`, `status`, `detail`, `instance`.
- Cross-tenant access returns **404**, never 403 (FR-PRJ-003).
- Mutations require an `Idempotency-Key` header where retry is plausible.

## Management API — `/api/**`

Auth: `Authorization: Bearer <jwt>`.

### Auth
| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/auth/register` | FR-ACC-001 |
| POST | `/api/auth/login` | FR-ACC-002, returns access and refresh tokens |
| POST | `/api/auth/refresh` | Exchange refresh token |

### Projects and environments
| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/projects` | Caller's projects only |
| POST | `/api/projects` | Creates three default environments (FR-ENV-001) |
| GET | `/api/projects/{projectKey}/environments` | |
| POST | `/api/projects/{projectKey}/environments` | |

### Keys
| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/projects/{projectKey}/environments/{envKey}/keys` | Prefix and metadata only |
| POST | `/api/projects/{projectKey}/environments/{envKey}/keys` | **Only response containing plaintext** (FR-KEY-002) |
| DELETE | `/api/.../keys/{keyId}` | Revoke; closes open streams (FR-STR-003) |

### Flags
| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/projects/{projectKey}/flags` | |
| POST | `/api/projects/{projectKey}/flags` | Creates configs in all environments (FR-FLG-003) |
| PATCH | `/api/projects/{projectKey}/flags/{flagKey}` | Name, description, visibility. Key immutable (FR-FLG-002) |
| POST | `/api/projects/{projectKey}/flags/{flagKey}/archive` | FR-FLG-005 |

### Configuration and targeting
| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/.../flags/{flagKey}/config/{envKey}` | |
| PATCH | `/api/.../flags/{flagKey}/config/{envKey}` | Kill switch, fallthrough value, rollout percentage, rollout salt |
| PUT | `/api/.../config/{envKey}/rules` | Replaces the ordered rule list atomically (FR-RUL-004) |
| PUT | `/api/.../config/{envKey}/overrides` | Replaces the override set |

Rules are replaced as a whole list rather than patched individually. Partial rule edits produce
transient invalid orderings that could be served to production for milliseconds.

`PATCH .../config/{envKey}` accepts `rolloutPercentage` as an integer 0–100 and stores it as basis
points (ADR-011). It does not accept `offValue`: a disabled flag returns `false` in v0.x and that
is not configurable, because the kill switch has to be unconditional (ADR-009). It accepts
`rolloutSalt`, which defaults to the flag key; changing it re-buckets every user of that flag, and
the endpoint says so in its OpenAPI description.

### Audit
| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/projects/{projectKey}/audit` | Newest first, paginated |

## Serving API — `/sdk/**`

Auth: `Authorization: Bearer <sdk key>`. Rate limited per key (NFR-SEC-005).

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/sdk/config` | Full ruleset for the key's environment, with ETag (FR-SRV-001) |
| POST | `/sdk/evaluate` | Server-side evaluation for thin clients (FR-SRV-002) |
| GET | `/sdk/stream` | SSE change notifications (FR-SRV-003) |

`GET /sdk/config` with a `client` key returns only client-side-visible flags (FR-KEY-005). This is
a security boundary, not a filter for convenience: the response reaches browsers.

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
that making it configurable later is a value change rather than a wire-format change.

### `POST /sdk/evaluate`

For clients that cannot hold a ruleset. It runs the same engine server-side, so it answers exactly
as an in-process evaluation would.

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

`context.key` is optional; omitting it skips overrides and rollout but still evaluates rules
(FR-EVL-005). `fallback` is required per flag — it is the value the caller has decided is safe,
and it is what an unknown flag, an unreadable flag or an internal error resolves to.

`reason` is one of `OFF`, `OVERRIDE`, `RULE_MATCH`, `ROLLOUT`, `FALLTHROUGH`, `FLAG_NOT_FOUND`,
`ERROR`. An unknown flag is **200 with the caller's fallback**, never 404 (FR-EVL-007): a thin
client must not get an HTTP error where a thick client gets its fallback. A flag that exists but
is not readable by this key reports `FLAG_NOT_FOUND` too, so the response does not disclose it.

### Stream

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
| 401 | Missing, malformed, or revoked key |
| 400 | Malformed request body on `POST /sdk/evaluate` |
| 429 | Rate limit exceeded, with `Retry-After` |
| 503 | Cache not ready; SDK retries with backoff |

There is deliberately no 404 for an unknown flag. 404 on `/sdk/**` means a path that does not
exist, nothing more.
