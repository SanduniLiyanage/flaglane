import type { Evaluation, FlaglaneClient, Logger } from "@flaglane/sdk";
import { FREE_SHIPPING, NEW_CHECKOUT } from "./settings.ts";

export interface Shopper {
  readonly key: string;
  readonly name: string;
  readonly country: string;
  readonly note?: string;
}

export interface Product {
  readonly name: string;
  readonly origin: string;
  readonly priceCents: number;
}

/** The shoppers the page can switch between. The seed gives qa-tester an override. */
export const shoppers: readonly [Shopper, ...Shopper[]] = [
  { key: "amara", name: "Amara", country: "LK" },
  { key: "ben", name: "Ben", country: "GB" },
  { key: "chen", name: "Chen", country: "US" },
  { key: "divya", name: "Divya", country: "IN" },
  { key: "qa-tester", name: "QA tester", country: "GB", note: "always sees the new checkout" },
];

export const products: readonly Product[] = [
  { name: "Uva black tea, 100 g", origin: "Uva", priceCents: 1200 },
  { name: "Nuwara Eliya green tea, 100 g", origin: "Nuwara Eliya", priceCents: 1450 },
  { name: "Dimbula breakfast blend, 250 g", origin: "Dimbula", priceCents: 1800 },
  { name: "Silver tips, 50 g", origin: "Ruhuna", priceCents: 3900 },
];

/**
 * A hundred anonymous visitors, evaluated on every page view so the page can show the rollout as
 * it moves. Evaluation is in process, so this costs microseconds, not a hundred requests.
 */
export const crowd: readonly string[] = Array.from(
  { length: 100 },
  (_, i) => `visitor-${String(i + 1).padStart(3, "0")}`,
);

export function findShopper(key: string | null): Shopper {
  return shoppers.find((shopper) => shopper.key === key) ?? shoppers[0];
}

/** The last thing the SDK reported, so the page can show what it is doing. */
export class SdkLog implements Logger {
  last: { readonly message: string; readonly at: Date } | undefined;

  warn(message: string, error?: unknown): void {
    this.last = { message, at: new Date() };
    console.warn(`[sdk] ${message}${error === undefined ? "" : ` (${describe(error)})`}`);
  }
}

export interface View {
  readonly shopper: Shopper;
  readonly newCheckout: Evaluation;
  readonly freeShipping: Evaluation;
  /** The crowd's keys that get the new checkout, in crowd order. */
  readonly crowdOn: readonly string[];
  readonly sdk: {
    readonly configured: boolean;
    readonly ready: boolean;
    readonly version: number | undefined;
    readonly lastMessage: SdkLog["last"];
  };
}

/** Everything one page view shows. Every flag is read with the value that is safe if Flaglane is down. */
export function viewFor(flags: FlaglaneClient, log: SdkLog, shopper: Shopper, configured: boolean): View {
  const context = { key: shopper.key, country: shopper.country };
  return {
    shopper,
    newCheckout: flags.evaluate(NEW_CHECKOUT, context, false),
    freeShipping: flags.evaluate(FREE_SHIPPING, context, false),
    crowdOn: crowd.filter((key) => flags.isOn(NEW_CHECKOUT, { key }, false)),
    sdk: { configured, ready: flags.ready, version: flags.version, lastMessage: log.last },
  };
}

export function price(cents: number): string {
  return `$${(cents / 100).toFixed(2)}`;
}

function describe(error: unknown): string {
  if (error instanceof Error) {
    const cause = error.cause as { code?: unknown } | undefined;
    return typeof cause?.code === "string" ? cause.code : error.message;
  }
  return String(error);
}
