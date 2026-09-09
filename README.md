# Flaglane

Feature flags and remote configuration for teams that want to ship code and decide later who sees it.

Flaglane lets you deploy a feature turned off, switch it on for a few users, roll it out to a
percentage of traffic, and turn it off again in under a second — without redeploying your
application.

## Why

Shipping a feature and releasing a feature are different events, but most teams are forced to do
them at the same time. That makes every release a risk, and every rollback a deploy.

Flaglane separates the two. Your code ships with the feature behind a flag. Who sees it is a
runtime decision you control from a dashboard.

## How it works

Add the SDK to your application once:

```ts
import { FlaglaneClient } from "@flaglane/sdk";

const flags = await FlaglaneClient.init({ sdkKey: process.env.FLAGLANE_SDK_KEY });

if (flags.isOn("new-checkout", { key: user.id, country: user.country })) {
  renderNewCheckout();
} else {
  renderLegacyCheckout();
}
```

That is the last deploy you need. From then on, the rollout is controlled from the dashboard.

The SDK downloads the full ruleset for its environment once at startup, evaluates flags in memory
in microseconds, and receives updates over a live stream. **If Flaglane is unreachable, your
application keeps running** on the last known ruleset, and failing that, on the default value you
specified in code. Flaglane being down must never take your application down.

## Features

- Boolean flags with per-environment configuration
- Explicit user targeting, attribute-based targeting rules, and percentage rollouts
- Rollouts are monotone: raising a percentage never takes the feature away from a user who has it
- Consistent bucketing: a user's assignment is stable across servers, restarts and redeploys
- Kill switch: one click disables a flag everywhere
- In-process evaluation (fast) and remote evaluation (for thin clients)
- Live updates over Server-Sent Events, with polling fallback
- Projects and environments with isolated API keys
- Separate server-side and client-side keys, so browser keys cannot read backend-only flags
- Append-only audit log of every change

## Not included

Flaglane is a flag engine, not an experimentation platform. It does not include:

- A/B test statistics or experiment analysis
- Multivariate flags (booleans only in v0.x)
- Regular-expression targeting. Neither Java nor JavaScript can put a timeout on a regex match,
  so a bad pattern would stall the thread evaluating it — which, with in-process evaluation, is
  your thread, not ours. `CONTAINS`, `STARTS_WITH` and `ENDS_WITH` are supported instead
- Role-based access control, SSO, or approval workflows
- Scheduled flag changes
- SDKs beyond TypeScript

If you need experimentation with a statistics engine, use GrowthBook. If you need enterprise
governance, use Unleash or Flagsmith.

## Running locally

```bash
git clone https://github.com/SanduniLiyanage/flaglane.git
cd flaglane
docker compose up
```

The API is on `http://localhost:8080`, Swagger UI on `http://localhost:8080/swagger-ui.html`,
and the dashboard on `http://localhost:5173`.

See [docs/](docs/) for architecture, API contract, database design and testing strategy.

## Stack

Java 21, Spring Boot 3, PostgreSQL, Flyway, React with TypeScript, Docker.

## Licence

Apache License 2.0.
