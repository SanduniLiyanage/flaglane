# @flaglane/sdk

Feature flags evaluated in your process, from a ruleset your [Flaglane](https://github.com/SanduniLiyanage/flaglane)
server serves. Checking a flag costs a hash and a few comparisons — no network call — and if
Flaglane is down your application keeps working.

```bash
npm install @flaglane/sdk
```

```ts
import { FlaglaneClient } from "@flaglane/sdk";

const flags = await FlaglaneClient.init({
  sdkKey: process.env.FLAGLANE_SDK_KEY,
  baseUrl: "https://flags.example.com",
});

if (flags.isOn("new-checkout", { key: user.id, country: user.country }, false)) {
  renderNewCheckout();
} else {
  renderLegacyCheckout();
}
```

## How it behaves

- **`init` downloads the environment's ruleset, then resolves.** If the ruleset has not arrived
  within `initTimeoutMs` (3 seconds by default), it resolves anyway and every flag answers with its
  fallback until the ruleset arrives. Your startup is never held longer. `init` never rejects.
- **`isOn` evaluates in memory** and never makes a network call. It never throws, whatever it is
  given.
- **The fallback is yours.** The third argument is what a flag answers when it cannot be evaluated:
  before the first ruleset arrives, for a flag that does not exist, or for one your key may not
  read. Choose the value that is safe if Flaglane is unreachable. It defaults to `false`.
- **Changes arrive within moments.** From 0.2, the SDK holds a stream to Flaglane, which tells it
  when the ruleset changes, and it downloads the new one at once. Whenever there is no stream — a
  proxy closed it, Flaglane is restarting, or the server is 0.1 and has none — the SDK asks every
  five seconds instead, with the ETag of the ruleset it has, so an unchanged ruleset costs an empty
  `304`. While the stream works, it does not poll at all.
- **In Node, call `close()` when the process should exit.** An open stream, like any open
  connection, keeps a Node process running. A server never notices; a short-lived script that
  creates a client should call `close()` when it is done, or pass `streaming: false`. The SDK's
  timers never keep a process alive on their own.
- **If Flaglane goes away, nothing breaks.** The SDK keeps answering from the last ruleset it had,
  retries with exponential backoff and jitter up to 30 seconds, and picks up changes again when the
  server is back. A bad response never replaces a good ruleset.
- **A key over its rate limit waits.** Flaglane allows each key 600 requests a minute by default,
  an unchanged ruleset's `304` counting as a tenth, which is 500 processes polling on one key. An
  SDK refused with 429 keeps its ruleset and asks again when the server's `Retry-After` says. A
  client key is shared by every browser that opens your page, so a busy site raises the server's
  limit.

## The context

The context is the user a flag is evaluated for. `key` identifies them; it drives user overrides
and percentage rollouts, and a user always lands in the same bucket, on every server and every
restart. Every other field is an attribute that targeting rules can match on. Attributes are
strings, numbers or booleans; anything else is ignored. Comparison is exact and type-strict: the
number `1` never equals the string `"1"`, and `"LK"` never equals `"lk"`.

Without a `key`, evaluation is anonymous: overrides and rollouts are skipped, and rules still
apply. An attribute named `key` cannot be targeted, because that name is the user key.

## Server and client keys

A **server key** (`flg_srv_…`) reads every flag in its environment. Keep it on servers.

A **client key** (`flg_cli_…`) reads only flags marked client-side visible, and its ruleset carries
no user overrides at all, because it reaches browsers and override user keys identify real people.
User overrides therefore do not apply to client keys; target specific users in the browser with a
rule on an attribute instead.

## Options

| Option | Default | |
| --- | --- | --- |
| `sdkKey` | — | Required. A server or client key for one environment |
| `baseUrl` | `http://localhost:8080` | Where your Flaglane server is |
| `initTimeoutMs` | `3000` | The longest `init` waits for the first ruleset |
| `pollIntervalMs` | `5000` | How often to check for changes |
| `maxBackoffMs` | `30000` | The longest wait between attempts while Flaglane is unreachable |
| `requestTimeoutMs` | `10000` | How long one request may take |
| `streaming` | `true` | Whether to hold a stream for changes. `false` polls only, as 0.1 did |
| `streamIdleTimeoutMs` | `60000` | How long a stream may stay silent before the SDK reconnects; Flaglane sends something at least every 30 seconds |
| `fetch` | `globalThis.fetch` | A `fetch` to use instead |
| `logger` | `console.warn` | Where to report problems; it never receives your key |

`evaluate(flagKey, context, fallback)` answers like `isOn` with the reason as well: `OFF`,
`OVERRIDE`, `RULE_MATCH`, `ROLLOUT`, `FALLTHROUGH`, `FLAG_NOT_FOUND` or `ERROR`. `ready` says
whether a ruleset has arrived, `version` which one is in use, `streaming` whether a stream is open
now, and `close()` stops polling and ends the stream.

## Requirements

Node 20 or later, or any current browser: the SDK uses only `fetch`, `TextEncoder`, `TextDecoder`
and `AbortController`, and has no dependencies. It reads the stream from a `fetch` body rather than
with `EventSource`, which cannot send the key in a header. ES modules only.

## Correctness

The SDK and the server are two implementations of one specification, and they are tested against
the same committed fixtures: every branch of the resolution order, the comparisons where Java and
JavaScript would naturally disagree, and the bucket of each of 100,000 user keys — non-ASCII keys
and keys containing `:` among them — against the reference MurmurHash3. See
[docs/TESTING.md](https://github.com/SanduniLiyanage/flaglane/blob/main/docs/TESTING.md), suites 8
and 9.

## Licence

Apache License 2.0.
