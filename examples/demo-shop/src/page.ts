import type { Evaluation, Reason } from "@flaglane/sdk";
import { FREE_SHIPPING, NEW_CHECKOUT } from "./settings.ts";
import { type View, crowd, price, products, shoppers } from "./shop.ts";

const reasons: Record<Reason, string> = {
  OFF: "the flag is switched off",
  OVERRIDE: "a user override names this shopper",
  RULE_MATCH: "a targeting rule matched",
  ROLLOUT: "inside the percentage rollout",
  FALLTHROUGH: "no override, rule or rollout applied",
  FLAG_NOT_FOUND: "not in the ruleset, so the fallback",
  ERROR: "could not be evaluated, so the safe value",
};

export function renderPage(view: View): string {
  const { shopper } = view;
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Hilltop Tea · Flaglane demo</title>
<style>${styles}</style>
</head>
<body>
<header>
  <div class="brand">Hilltop Tea</div>
  <nav aria-label="Shop as">
    ${shoppers
      .map(
        (s) =>
          `<a href="/?shopper=${encodeURIComponent(s.key)}"${s.key === shopper.key ? ' aria-current="true"' : ""}>${escape(s.name)} <span>${escape(s.country)}</span></a>`,
      )
      .join("\n    ")}
  </nav>
</header>
<main id="live">
  <section class="shop">
    ${
      view.freeShipping.value
        ? `<p class="banner">Free shipping to Sri Lanka and India on every order.</p>`
        : ""
    }
    <ul class="products">
      ${products
        .map(
          (p) =>
            `<li><div class="leaf" aria-hidden="true"></div><h3>${escape(p.name)}</h3><p>${escape(p.origin)}</p><strong>${price(p.priceCents)}</strong></li>`,
        )
        .join("\n      ")}
    </ul>
    ${view.newCheckout.value ? newCheckout : classicCheckout}
  </section>
  <aside>
    <h2>Behind the counter</h2>
    <p class="who">Shopping as <strong>${escape(shopper.name)}</strong>, ${escape(shopper.country)}${shopper.note === undefined ? "" : ` — ${escape(shopper.note)}`}.</p>
    <table>
      <tr><th>Flag</th><th>Value</th><th>Why</th></tr>
      ${row(NEW_CHECKOUT, view.newCheckout)}
      ${row(FREE_SHIPPING, view.freeShipping)}
    </table>
    <h3><code>${NEW_CHECKOUT}</code> across ${crowd.length} visitors</h3>
    <div class="crowd" role="img" aria-label="${view.crowdOn.length} of ${crowd.length} visitors see the new checkout">
      ${crowd.map((key) => `<i${view.crowdOn.includes(key) ? ' class="on"' : ""} title="${key}"></i>`).join("")}
    </div>
    <p><strong>${view.crowdOn.length} of ${crowd.length}</strong> see the new checkout. Raise the rollout and
    the squares that are lit stay lit.</p>
    <h3>SDK</h3>
    <p>${sdkStatus(view)}</p>
    ${
      view.sdk.lastMessage === undefined
        ? ""
        : `<p class="log"><time>${view.sdk.lastMessage.at.toISOString().slice(11, 19)} UTC</time> ${escape(view.sdk.lastMessage.message)}</p>`
    }
    <p class="hint">Try <code>npm run flag -- rollout 60</code>, or stop Flaglane and watch this page keep working.</p>
  </aside>
</main>
<script>
// Re-render in place every second, so a flag change shows up without a reload.
setInterval(async () => {
  try {
    const response = await fetch(location.href, { cache: "no-store" });
    const next = new DOMParser().parseFromString(await response.text(), "text/html");
    document.getElementById("live").replaceWith(next.getElementById("live"));
  } catch {}
}, 1000);
</script>
</body>
</html>
`;
}

const newCheckout = `<div class="checkout new">
      <h2>Express checkout <span class="tag">new</span></h2>
      <p>One page. Saved address, card on file, done.</p>
      <button type="button">Pay now</button>
    </div>`;

const classicCheckout = `<div class="checkout">
      <h2>Checkout</h2>
      <p>Step 1 of 4: review your basket.</p>
      <button type="button">Continue</button>
    </div>`;

function row(flag: string, evaluation: Evaluation): string {
  const value = evaluation.value ? "on" : "off";
  return `<tr><td><code>${flag}</code></td><td class="${value}">${value}</td><td><code>${evaluation.reason}</code> ${reasons[evaluation.reason]}</td></tr>`;
}

function sdkStatus(view: View): string {
  if (!view.sdk.configured) {
    return "No SDK key, so every flag is at its fallback. Run <code>npm run seed</code>, then restart the shop.";
  }
  if (!view.sdk.ready || view.sdk.version === undefined) {
    return "No ruleset yet, so every flag is at its fallback. The SDK keeps trying.";
  }
  return `Evaluating in process from ruleset version <strong>${view.sdk.version}</strong>, checked for changes every five seconds.`;
}

function escape(text: string): string {
  return text.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

const styles = `
:root { --bg: #faf7f2; --panel: #fff; --ink: #1f2421; --muted: #5c645f; --line: #e3ddd2;
  --accent: #2f6b4f; --accent-ink: #fff; --on: #2f6b4f; --off: #c9c2b6; --banner: #fdf0c4; }
@media (prefers-color-scheme: dark) {
  :root { --bg: #151816; --panel: #1e2320; --ink: #e8ebe8; --muted: #9aa49e; --line: #2e3531;
    --accent: #7fc4a0; --accent-ink: #10221a; --on: #7fc4a0; --off: #3a423d; --banner: #3b3420; }
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--ink);
  font: 16px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
header { display: flex; flex-wrap: wrap; gap: 12px 24px; align-items: center; justify-content: space-between;
  padding: 16px clamp(16px, 4vw, 40px); border-bottom: 1px solid var(--line); }
.brand { font-weight: 700; font-size: 20px; letter-spacing: 0.02em; }
nav { display: flex; flex-wrap: wrap; gap: 8px; }
nav a { color: var(--ink); text-decoration: none; padding: 4px 12px; border: 1px solid var(--line);
  border-radius: 999px; font-size: 14px; }
nav a span { color: var(--muted); }
nav a[aria-current] { background: var(--accent); color: var(--accent-ink); border-color: var(--accent); }
nav a[aria-current] span { color: inherit; opacity: 0.8; }
main { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 380px); gap: 32px;
  padding: 24px clamp(16px, 4vw, 40px); max-width: 1200px; margin: 0 auto; }
@media (max-width: 860px) { main { grid-template-columns: minmax(0, 1fr); } }
.banner { margin: 0 0 16px; padding: 10px 16px; background: var(--banner); border-radius: 8px; font-weight: 600; }
.products { list-style: none; margin: 0 0 24px; padding: 0; display: grid; gap: 16px;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); }
.products li { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 16px; }
.products h3 { font-size: 15px; margin: 12px 0 2px; }
.products p { margin: 0 0 8px; color: var(--muted); font-size: 14px; }
.leaf { height: 72px; border-radius: 6px; background: linear-gradient(135deg, var(--accent), transparent 140%); opacity: 0.35; }
.checkout { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 20px; }
.checkout h2 { margin: 0 0 4px; font-size: 20px; }
.checkout p { margin: 0 0 16px; color: var(--muted); }
.checkout.new { border: 2px solid var(--accent); }
.tag { font-size: 12px; vertical-align: middle; padding: 2px 8px; border-radius: 999px;
  background: var(--accent); color: var(--accent-ink); }
button { font: inherit; padding: 8px 20px; border-radius: 6px; border: 1px solid var(--line);
  background: var(--bg); color: var(--ink); }
.new button { background: var(--accent); color: var(--accent-ink); border-color: var(--accent); }
aside { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 20px; align-self: start; }
aside h2 { margin: 0 0 8px; font-size: 18px; }
aside h3 { margin: 20px 0 8px; font-size: 15px; }
aside p { margin: 8px 0; font-size: 14px; }
table { width: 100%; border-collapse: collapse; font-size: 14px; }
th, td { text-align: left; padding: 6px 4px; border-bottom: 1px solid var(--line); vertical-align: top; }
th { color: var(--muted); font-weight: 500; }
td.on { color: var(--on); font-weight: 700; }
td.off { color: var(--muted); font-weight: 700; }
code { font: 13px ui-monospace, "Cascadia Code", Menlo, monospace; }
td:first-child code { white-space: nowrap; }
.crowd { display: grid; grid-template-columns: repeat(20, 1fr); gap: 3px; }
.crowd i { aspect-ratio: 1; border-radius: 2px; background: var(--off); }
.crowd i.on { background: var(--on); }
.log { color: var(--muted); }
.log time { font-variant-numeric: tabular-nums; }
.hint { color: var(--muted); }
`;
