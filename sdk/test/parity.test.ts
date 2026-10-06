import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { bucket } from "../src/bucketing.js";
import { userFrom } from "../src/context.js";
import { Evaluator } from "../src/evaluator.js";
import { murmur3X86_32 } from "../src/murmur3.js";
import { compileRule } from "../src/rules.js";
import { parseRuleset } from "../src/ruleset.js";
import {
  type BucketingFixture,
  type ComparisonCase,
  type ParityFixture,
  fixture,
  fixtureText,
} from "./fixtures.js";

/**
 * Suite 9, the SDK's half (FR-SDK-007). The server runs the same fixtures in
 * ParityFixturesTest; both must produce every expected answer, so the two implementations of
 * one specification cannot drift apart without a test failing.
 */

const quiet = { warn: () => {} };
const parity = fixture<ParityFixture>("evaluation-parity.json");
const comparison = fixture<{ cases: ComparisonCase[] }>("comparison-semantics.json");
const bucketing = fixture<BucketingFixture>("bucketing-vectors.json");

describe("evaluation parity", () => {
  const evaluator = new Evaluator({ logger: quiet });

  it.each(parity.cases)("$name", (row) => {
    const ruleset = parseRuleset(parity.rulesets[row.ruleset]);
    const user = userFrom(row.context.key, row.context.attributes);

    const evaluation = evaluator.evaluate(ruleset, row.flag, user, row.fallback);

    expect(evaluation).toEqual(row.expected);
  });

  it("parses every fixture ruleset", () => {
    for (const [name, served] of Object.entries(parity.rulesets)) {
      expect(parseRuleset(served), name).toBeDefined();
    }
  });
});

describe("comparison semantics, suite 4a, rule by rule", () => {
  it.each(comparison.cases)("$name", (row) => {
    const rule = compileRule({
      priority: 0,
      attribute: "attribute" in row ? row.attribute : "attr",
      operator: row.operator,
      matchValues: row.matchValues,
      resultValue: true,
    });

    const outcome =
      rule.malformation !== undefined
        ? "malformed"
        : rule.matches(userFrom("u-1", row.attributes))
          ? "match"
          : "no-match";

    expect(outcome).toBe(row.expected);
  });
});

describe("comparison semantics, suite 4a, through the whole engine", () => {
  const evaluator = new Evaluator({ logger: quiet });

  it.each(comparison.cases)("$name", (row) => {
    const ruleset = parseRuleset({
      environment: "production",
      version: 1,
      flags: [
        {
          key: "flag",
          enabled: true,
          offValue: false,
          fallthroughValue: false,
          rolloutBasisPoints: 0,
          rolloutSalt: "flag",
          overrides: [],
          rules: [
            {
              priority: 0,
              attribute: "attribute" in row ? row.attribute : "attr",
              operator: row.operator,
              matchValues: row.matchValues,
              resultValue: true,
            },
          ],
        },
      ],
    });

    const evaluation = evaluator.evaluate(ruleset, "flag", userFrom("u-1", row.attributes), false);

    const expected = {
      match: { value: true, reason: "RULE_MATCH" },
      "no-match": { value: false, reason: "FALLTHROUGH" },
      malformed: { value: false, reason: "ERROR" },
    }[row.expected];
    expect(evaluation).toEqual(expected);
  });
});

describe("bucketing parity", () => {
  it.each(bucketing.murmur3)("murmur3 of $input.length bytes with seed $seed is $hash", (row) => {
    const hash = murmur3X86_32(new TextEncoder().encode(row.input), row.seed);

    expect(hash.toString(16).padStart(8, "0")).toBe(row.hash);
  });

  it.each(bucketing.buckets)("bucket $bucket", (row) => {
    expect(bucket(row.salt, row.userKey)).toBe(row.bucket);
  });

  it("every bucket of the committed key set matches the shared digest", () => {
    const text = fixtureText(bucketing.keySet.file);
    expect(text.includes("\r"), "the key fixture must keep LF line endings").toBe(false);
    const keys = text.split("\n").slice(0, -1);
    expect(keys).toHaveLength(100_000);

    const digest = createHash("sha256");
    for (const key of keys) {
      digest.update(`${bucket(bucketing.keySet.salt, key)}\n`);
    }

    expect(digest.digest("hex")).toBe(bucketing.keySet.sha256);
  });
});
