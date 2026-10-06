# Demo shop

Hilltop Tea is a small storefront whose checkout and shipping banner are decided by Flaglane flags.
It is the quickest way to see Flaglane work end to end without a dashboard. Change a flag through the
management API and the shop changes within five seconds. Stop Flaglane and the shop keeps serving.

The shop is one `FlaglaneClient` with a server key, evaluating in process:

```ts
const flags = await FlaglaneClient.init({ sdkKey, baseUrl: flaglaneUrl });

flags.evaluate("new-checkout", { key: shopper.key, country: shopper.country }, false);
```

## What it shows

- **`new-checkout`** is rolled out to 30% of shoppers, with a user override that always gives it to
  `qa-tester`. The page shows the checkout the selected shopper gets. It also evaluates the flag for
  100 visitors and draws who has it, so moving the rollout visibly lights squares, and raising it
  never turns one off.
- **`free-shipping`** has one targeting rule: `country IN [LK, IN]`. Amara and Divya see the banner;
  Ben and Chen do not.
- A side panel shows the following:
  - each flag's value and the reason the engine gives for it (`ROLLOUT`, `OVERRIDE`, `RULE_MATCH`
    and so on);
  - the ruleset version the SDK holds;
  - the last thing the SDK reported.

The page re-renders itself every second, so a change appears without a reload.

## Run it

You need Node 22.18 or later, which runs the TypeScript directly, and Flaglane running. For a local
stack, see [Running locally](../../README.md#running-locally).

```bash
# Until @flaglane/sdk is on npm, the shop links to the SDK in this repository.
npm --prefix sdk ci && npm --prefix sdk run build

cd examples/demo-shop
npm install
npm run seed     # an account, the demo-shop project, two flags and a server key, saved in .env
npm start        # http://localhost:3000
```

`npm run seed` can be run again at any time. It puts both flags back to where the demo starts, and
it keeps the account and key it saved. Set `FLAGLANE_URL` if Flaglane is not on
`http://localhost:8080`. `.env.example` lists every setting.

## Change a flag

In a second terminal, from `examples/demo-shop`:

```bash
npm run flag -- rollout 60           # more visitors get it, and nobody who had it loses it
npm run flag -- off                  # kill switch: off for everyone, qa-tester's override included
npm run flag -- on                   # back on, with the rollout and override it had
npm run flag -- show free-shipping
```

Each command is one call to the management API, the same call a dashboard would make:

```http
PATCH /api/projects/demo-shop/flags/new-checkout/config/development
Authorization: Bearer <accessToken from POST /api/auth/login>
Content-Type: application/json

{ "rolloutPercentage": 60 }
```

The shop's SDK checks for a new ruleset every five seconds, so the change appears within that time.

## Stop Flaglane

Run `docker compose stop api` from the repository root and keep using the shop. The SDK reports once
that it cannot reach Flaglane, then carries on evaluating from the ruleset it already has: rollout,
rules and override included. Run `docker compose start api` and it reports that updates resume.

A shop started while Flaglane is down has no ruleset to fall back on. It serves every flag at the
value its code passed as safe, here `false`, until a ruleset arrives.

## Check it

```bash
npm run verify -- propagation   # with Flaglane and the shop running
npm run verify -- offline       # after stopping Flaglane
```

`propagation` makes four changes to `new-checkout` and times each one from the end of the `PATCH`
to the shop serving the new ruleset version. It fails if any takes longer than the five-second poll
interval plus one second for the request. It also checks that raising the rollout takes the feature
from nobody, and that the kill switch beats an override. `offline` checks that the shop still
applies the override, the rule and the rollout with Flaglane unreachable. CI runs both against
`docker compose` on every push.

`propagation` leaves `new-checkout` at 60%. `npm run seed` puts it back to 30%.

## What is in here

| File | What it does |
| --- | --- |
| `src/server.ts` | The shop: one SDK client and two routes, `/` and `/state.json` |
| `src/shop.ts` | Shoppers, products, and the evaluations one page view makes |
| `src/page.ts` | The HTML |
| `src/seed.ts` | Sets the demo up through the management API |
| `src/flag.ts` | Changes one flag's configuration |
| `src/verify.ts` | Checks the two claims above against a running stack |
| `src/management.ts` | The management API calls the scripts share |

## Limitations

- **The SDK is linked from this repository**, not installed from npm, until 0.1.0 is published.
- **`npm run seed` registers an account wherever `FLAGLANE_URL` points.** Its generated password is
  saved in the gitignored `.env`. Against a shared instance, that is a real account.
- **This is a demonstration, not a reference storefront.** There is no cart, no payment and no
  Content-Security-Policy. The page fetches itself once a second, which is fine for a demo and
  wasteful anywhere else.
