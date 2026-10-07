import { describe, expect, it } from "vitest";
import { isKey, keyFrom } from "../src/keys";

describe("a key suggested from a name", () => {
  it.each([
    ["New checkout", "new-checkout"],
    ["  Storefront  ", "storefront"],
    ["Café — Pay Later!", "cafe-pay-later"],
    ["___", ""],
  ])("turns %j into %j", (name, key) => {
    expect(keyFrom(name)).toBe(key);
  });

  it("is always a valid key when it is not empty", () => {
    for (const name of ["New checkout", "a".repeat(100), "x-".repeat(40), "Ünïcödé 2026"]) {
      const key = keyFrom(name);
      expect(key === "" || isKey(key), `${name} → ${key}`).toBe(true);
    }
  });
});

describe("a key", () => {
  it.each(["a", "new-checkout", "a1", "x".repeat(63)])("accepts %s", (key) => {
    expect(isKey(key)).toBe(true);
  });

  it.each(["", "-a", "a-", "A", "a_b", "a:b", "x".repeat(64)])("refuses %j", (key) => {
    expect(isKey(key)).toBe(false);
  });
});
