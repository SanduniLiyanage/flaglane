import { describe, expect, it } from "vitest";
import { encodeUtf8 } from "../src/bucketing.js";
import { userFrom, userFromContext } from "../src/context.js";

// The evaluation hot path avoids TextEncoder and copying the context (docs/BENCHMARKS.md). These
// pin it to what it replaced; the parity suite then pins both to the server.

describe("encodeUtf8", () => {
  const samples = [
    "",
    "new-checkout:u-1042",
    "é",
    "ünïcödé-ключ",
    "ඓආඨහඕඞ-87994",
    "€",
    "߿ࠀ￿",
    "😀",
    "a😀b",
    "\ud83d",
    "\ude00",
    "\ude00\ud83d",
    "x\ud83dy",
    "\ud83d😀",
    "tail\ud83d",
  ];

  it.each(samples)("writes %j as TextEncoder does", (text) => {
    const into = new Uint8Array(text.length * 3 + 8);

    const end = encodeUtf8(text, into, 4);

    expect(Array.from(into.subarray(4, end))).toEqual(Array.from(new TextEncoder().encode(text)));
  });

  it("agrees with TextEncoder on every code unit", () => {
    for (let unit = 0; unit <= 0xffff; unit++) {
      const text = `a${String.fromCharCode(unit)}z`;
      const into = new Uint8Array(16);
      const end = encodeUtf8(text, into, 0);
      expect(Array.from(into.subarray(0, end))).toEqual(Array.from(new TextEncoder().encode(text)));
    }
  });
});

describe("userFromContext", () => {
  const contexts: Record<string, unknown>[] = [
    {},
    { key: "u-1" },
    { key: "", country: "LK" },
    { key: 42, plan: "pro", seats: 3, beta: true },
    { country: "LK", nested: { a: 1 }, list: [1], nothing: null, nan: Number.NaN, fn: () => 1 },
    { key: "u-2", key2: "not the key" },
  ];

  it.each(contexts)("reads %j as userFrom reads it split in two", (context) => {
    const { key, ...attributes } = context;

    expect(userFromContext(context)).toEqual(userFrom(key, attributes));
  });

  it("ignores inherited properties, as the copy it replaced did", () => {
    const context = Object.create({ inherited: "yes" }) as Record<string, unknown>;
    context.own = "here";

    expect([...userFromContext(context).attributes.keys()]).toEqual(["own"]);
  });
});
