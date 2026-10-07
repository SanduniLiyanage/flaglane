import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import type { RuleRequest } from "../src/api/schema";
import {
  describe as sentence,
  draftFrom,
  isValid,
  moved,
  newRule,
  OPERATORS,
  problemsOf,
  ruleIndexOf,
  rulesChanged,
  toRequest,
  warningOf,
  type Operator,
  type RuleDraft,
} from "../src/flags/rules";

interface Case {
  name: string;
  operator: string | null;
  attribute?: string;
  matchValues: unknown[] | null;
  expected: "match" | "no-match" | "malformed";
}

/** Suite 4a's rows, which RuleValidatorTest holds the server's write-time validation to. */
const cases = (
  JSON.parse(
    readFileSync(new URL("../../backend/src/test/resources/fixtures/comparison-semantics.json", import.meta.url), "utf8"),
  ) as { cases: Case[] }
).cases;

const STRING_ONLY = ["CONTAINS", "STARTS_WITH", "ENDS_WITH"];

/** What the server accepts on write, as RuleValidatorTest states it. */
function serverAccepts(row: Case): boolean {
  const stringOperatorWithANonString =
    STRING_ONLY.includes(row.operator ?? "") && row.matchValues?.length === 1 && typeof row.matchValues[0] !== "string";
  return row.expected !== "malformed" && !stringOperatorWithANonString;
}

/**
 * The rows the form can express: an operator it offers, and values of one supported type. It cannot
 * express an unknown operator, a missing value list, or a list mixing types, since it holds one
 * type per rule; those reach the API only from other clients.
 */
function expressible(row: Case): row is Case & { operator: Operator; matchValues: unknown[] } {
  if (row.operator === null || !(row.operator in OPERATORS) || row.matchValues === null || row.matchValues.length === 0) {
    return false;
  }
  const types = new Set(row.matchValues.map((value) => typeof value));
  return types.size === 1 && ["string", "number", "boolean"].includes([...types][0] as string);
}

const draft = (change: Partial<RuleDraft>): RuleDraft => ({ ...newRule(), attribute: "country", values: "LK", ...change });

describe("the rule editor's validation", () => {
  const expressibleCases = cases.filter(expressible);

  it("covers most of suite 4a's rows", () => {
    expect(expressibleCases.length).toBeGreaterThan(cases.length / 2);
  });

  it.each(expressibleCases.map((row) => [row.name, row] as const))(
    "agrees with the server's write-time validation: %s",
    (_, row) => {
      const rule: RuleRequest = {
        // An absent attribute means the fixture's default; null means none, which the form holds
        // as an empty field.
        attribute: row.attribute === undefined ? "attr" : (row.attribute ?? ""),
        operator: row.operator,
        matchValues: row.matchValues,
        resultValue: true,
      };

      expect(isValid(draftFrom(rule))).toBe(serverAccepts(row));
    },
  );

  it("names the attribute that is missing or too long", () => {
    expect(problemsOf(draft({ attribute: "  " })).attribute).toMatch(/Name the attribute/);
    expect(problemsOf(draft({ attribute: "a".repeat(101) })).attribute).toMatch(/at most 100/);
    expect(problemsOf(draft({ attribute: "a".repeat(100) })).attribute).toBeUndefined();
  });

  it("holds a single-value operator to one value and a list to at least one", () => {
    expect(problemsOf(draft({ operator: "EQUALS", values: "LK\nIN" })).values).toMatch(/exactly one value/);
    expect(problemsOf(draft({ operator: "IN", values: "\n  \n" })).values).toMatch(/at least one value/);
    expect(problemsOf(draft({ operator: "IN", values: Array(1001).fill("x").join("\n") })).values).toMatch(/at most 1000/);
  });

  it("refuses numbers and booleans that are not", () => {
    expect(problemsOf(draft({ type: "number", values: "1.5" })).values).toBeUndefined();
    expect(problemsOf(draft({ type: "number", values: "1,5" })).values).toMatch(/not a number/);
    expect(problemsOf(draft({ type: "number", values: "Infinity" })).values).toMatch(/not a number/);
    expect(problemsOf(draft({ type: "boolean", values: "yes" })).values).toMatch(/neither true nor false/);
  });

  it("keeps the text operators to text", () => {
    expect(problemsOf(draft({ operator: "CONTAINS", type: "number", values: "1" })).values).toMatch(/compares text only/);
  });

  it("accepts an empty text value, as the API does, and says what it matches", () => {
    const contains = draft({ operator: "CONTAINS", values: "" });

    expect(isValid(contains)).toBe(true);
    expect(warningOf(contains)).toMatch(/matches any text attribute/);
    expect(problemsOf(draft({ operator: "EQUALS", type: "number", values: "" })).values).toMatch(/Enter a value/);
  });

  it("warns, without refusing, that the SDK never matches a rule on key", () => {
    const rule = draft({ attribute: "key" });

    expect(isValid(rule)).toBe(true);
    expect(warningOf(rule)).toMatch(/never matches/);
  });
});

describe("a staged rule list", () => {
  const saved: RuleRequest[] = [
    { attribute: "country", operator: "IN", matchValues: ["LK", "IN"], resultValue: true },
    { attribute: "age", operator: "EQUALS", matchValues: [30], resultValue: false },
  ];

  it("round-trips the stored rules with their types", () => {
    const drafts = saved.map(draftFrom);

    expect(drafts.map(toRequest)).toEqual(saved);
    expect(rulesChanged(saved, drafts)).toBe(false);
  });

  it("counts a reorder, an edit, a removal or an addition as a change", () => {
    const drafts = saved.map(draftFrom);

    expect(rulesChanged(saved, moved(drafts, 0, 1))).toBe(true);
    expect(rulesChanged(saved, [{ ...(drafts[0] as RuleDraft), resultValue: false }, drafts[1] as RuleDraft])).toBe(true);
    expect(rulesChanged(saved, drafts.slice(1))).toBe(true);
    expect(rulesChanged(saved, [...drafts, newRule()])).toBe(true);
  });

  it("moves a rule within the list and no further", () => {
    expect(moved(["a", "b", "c"], 0, 1)).toEqual(["b", "a", "c"]);
    expect(moved(["a", "b", "c"], 2, -1)).toEqual(["a", "c", "b"]);
    expect(moved(["a", "b", "c"], 0, -1)).toEqual(["a", "b", "c"]);
    expect(moved(["a", "b", "c"], 2, 1)).toEqual(["a", "b", "c"]);
  });

  it("reads a list of values one per line, skipping blank lines", () => {
    expect(toRequest(draft({ operator: "IN", values: "LK\n\nIN\n" })).matchValues).toEqual(["LK", "IN"]);
    expect(toRequest(draft({ operator: "IN", type: "number", values: "1\n2.5" })).matchValues).toEqual([1, 2.5]);
  });

  it("reads which rule the API refused from its field name", () => {
    expect(ruleIndexOf("rules[3]")).toBe(3);
    expect(ruleIndexOf("rules[0].attribute")).toBe(0);
    expect(ruleIndexOf("rules")).toBeNull();
  });

  it("describes a rule in a sentence", () => {
    expect(sentence(draft({ operator: "IN", values: "LK\nIN" }))).toBe("country is one of LK, IN → true");
  });
});
