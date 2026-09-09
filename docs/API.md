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
| PATCH | `/api/.../flags/{flagKey}/config/{envKey}` | Kill switch, default, rollout |
| PUT | `/api/.../config/{envKey}/rules` | Replaces the ordered rule list atomically (FR-RUL-004) |
| PUT | `/api/.../config/{envKey}/overrides` | Replaces the override set |

Rules are replaced as a whole list rather than patched individually. Partial rule edits produce
transient invalid orderings that could be served to production for milliseconds.

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
      "defaultValue": false,
      "rolloutPercentage": 30,
      "overrides": [{ "userKey": "u-1042", "value": true }],
      "rules": [
        { "priority": 0, "attribute": "country", "operator": "IN",
          "values": ["LK"], "resultValue": true }
      ]
    }
  ]
}
```

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
| 404 | Unknown flag in `POST /sdk/evaluate` |
| 429 | Rate limit exceeded, with `Retry-After` |
| 503 | Cache not ready; SDK retries with backoff |
