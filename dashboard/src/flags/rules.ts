// The rule editor's staged list (FR-UI-004, FR-RUL-004). Rules are replaced as one ordered list in
// one request, so the editor stages the whole list and saves it once. Every check the API makes on
// write — RuleValidator's definition of an applicable rule (ADR-019) and the limits of ADR-023 — is
// made here as the rule is edited, so a problem shows beside the field rather than after Save.

import type { RuleRequest } from "../api/schema";

export type Operator = RuleRequest["operator"];
export type ValueType = "string" | "number" | "boolean";

interface OperatorInfo {
  readonly label: string;
  /** One match value, or a list of one or more. */
  readonly shape: "single" | "list";
  /** CONTAINS, STARTS_WITH and ENDS_WITH compare strings only (FR-RUL-009). */
  readonly stringOnly: boolean;
}

/** FR-RUL-003, in the order the editor offers them. */
export const OPERATORS: Readonly<Record<Operator, OperatorInfo>> = {
  EQUALS: { label: "is", shape: "single", stringOnly: false },
  NOT_EQUALS: { label: "is not", shape: "single", stringOnly: false },
  IN: { label: "is one of", shape: "list", stringOnly: false },
  NOT_IN: { label: "is none of", shape: "list", stringOnly: false },
  CONTAINS: { label: "contains", shape: "single", stringOnly: true },
  STARTS_WITH: { label: "starts with", shape: "single", stringOnly: true },
  ENDS_WITH: { label: "ends with", shape: "single", stringOnly: true },
};

/** ADR-023. */
export const MAX_RULES = 100;
export const MAX_VALUES = 1000;
export const MAX_ATTRIBUTE = 100;

/** One rule as the form holds it: match values as typed, one per line for a list. */
export interface RuleDraft {
  /** Identifies the row while the list is reordered; never sent. */
  readonly id: number;
  readonly attribute: string;
  readonly operator: Operator;
  readonly type: ValueType;
  readonly values: string;
  readonly resultValue: boolean;
}

export interface RuleProblems {
  readonly attribute?: string;
  readonly values?: string;
}

/** A JSON number, as the API reads it. */
const NUMBER = /^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/;

let nextId = 1;

export function newRule(): RuleDraft {
  return { id: nextId++, attribute: "", operator: "IN", type: "string", values: "", resultValue: true };
}

/** A stored rule as the form holds it. */
export function draftFrom(rule: RuleRequest): RuleDraft {
  const first = rule.matchValues[0];
  const type: ValueType = typeof first === "number" ? "number" : typeof first === "boolean" ? "boolean" : "string";
  return {
    id: nextId++,
    attribute: rule.attribute,
    operator: rule.operator,
    type,
    values: rule.matchValues.map((value) => String(value)).join("\n"),
    resultValue: rule.resultValue,
  };
}

/** The match values as typed: one per line for a list, the whole field for a single value. */
function lines(draft: RuleDraft): string[] {
  if (OPERATORS[draft.operator].shape === "single") {
    return [draft.values];
  }
  return draft.values.split(/\r?\n/).filter((line) => line.trim() !== "");
}

/** What is wrong with the rule, by field; empty when the API would accept it. */
export function problemsOf(draft: RuleDraft): RuleProblems {
  const problems: { attribute?: string; values?: string } = {};
  if (draft.attribute.trim() === "") {
    problems.attribute = "Name the attribute the rule compares.";
  } else if (draft.attribute.length > MAX_ATTRIBUTE) {
    problems.attribute = `An attribute name is at most ${MAX_ATTRIBUTE} characters.`;
  }

  const operator = OPERATORS[draft.operator];
  const values = lines(draft);
  if (operator.stringOnly && draft.type !== "string") {
    problems.values = `"${operator.label}" compares text only.`;
  } else if (operator.shape === "single" && draft.values.includes("\n")) {
    problems.values = `"${operator.label}" takes exactly one value.`;
  } else if (operator.shape === "list" && values.length === 0) {
    problems.values = "Enter at least one value, one per line.";
  } else if (operator.shape === "single" && draft.values.trim() === "" && draft.type !== "string") {
    problems.values = "Enter a value.";
  } else if (values.length > MAX_VALUES) {
    problems.values = `A rule holds at most ${MAX_VALUES} values.`;
  } else if (draft.type === "number") {
    const bad = values.find((value) => !NUMBER.test(value.trim()) || !Number.isFinite(Number(value)));
    if (bad !== undefined) {
      problems.values = `${JSON.stringify(bad)} is not a number.`;
    }
  } else if (draft.type === "boolean") {
    const bad = values.find((value) => value.trim() !== "true" && value.trim() !== "false");
    if (bad !== undefined) {
      problems.values = `${JSON.stringify(bad)} is neither true nor false.`;
    }
  }
  return problems;
}

export function isValid(draft: RuleDraft): boolean {
  return Object.keys(problemsOf(draft)).length === 0;
}

/**
 * A warning that does not stop a save, for a rule the API accepts that probably does not do what was
 * meant. A rule on `key` is accepted, and `POST /sdk/evaluate` can match it, but the SDK takes `key`
 * as the user key and never as an attribute (ADR-026), so in an application using the SDK it never
 * matches. An empty text value is a value, the empty string, and is compared as one.
 */
export function warningOf(draft: RuleDraft): string | null {
  if (draft.attribute === "key") {
    return "The SDK reads key as the user key, never as an attribute, so this rule never matches there. Target a user key with an override instead.";
  }
  if (OPERATORS[draft.operator].shape === "single" && draft.type === "string" && draft.values === "") {
    switch (draft.operator) {
      case "EQUALS":
        return "The value is empty, so this matches only an attribute that is an empty string.";
      case "NOT_EQUALS":
        return "The value is empty, so this matches any text attribute that is not an empty string.";
      default:
        return `The value is empty, and every piece of text ${OPERATORS[draft.operator].label} the empty string, so this matches any text attribute.`;
    }
  }
  return null;
}

/** The rule as the API takes it. Only for a valid draft. */
export function toRequest(draft: RuleDraft): RuleRequest {
  const values = lines(draft).map((value): string | number | boolean => {
    switch (draft.type) {
      case "number":
        return Number(value.trim());
      case "boolean":
        return value.trim() === "true";
      default:
        // Text is compared exactly (FR-RUL-007), so a list line is kept as typed but for its
        // line break; a single value is kept whole.
        return value;
    }
  });
  return { attribute: draft.attribute, operator: draft.operator, matchValues: values, resultValue: draft.resultValue };
}

/** Whether the staged list differs from the saved one. An invalid draft always counts as a change. */
export function rulesChanged(saved: readonly RuleRequest[], drafts: readonly RuleDraft[]): boolean {
  if (saved.length !== drafts.length) {
    return true;
  }
  return drafts.some((draft, i) => !isValid(draft) || JSON.stringify(toRequest(draft)) !== JSON.stringify(saved[i]));
}

/** The list with the rule at `index` moved by `by` places, or unchanged at either end. */
export function moved<T>(list: readonly T[], index: number, by: -1 | 1): T[] {
  const target = index + by;
  if (target < 0 || target >= list.length) {
    return [...list];
  }
  const next = [...list];
  [next[index], next[target]] = [next[target] as T, next[index] as T];
  return next;
}

/** A rule in a sentence, for the list: `country is one of LK, IN → true`. */
export function describe(draft: RuleDraft): string {
  const values = lines(draft);
  const shown = values.length > 3 ? `${values.slice(0, 3).join(", ")} and ${values.length - 3} more` : values.join(", ");
  return `${draft.attribute || "…"} ${OPERATORS[draft.operator].label} ${shown || "…"} → ${draft.resultValue}`;
}

/** Which rule a validation error from the API names: `rules[2]` or `rules[2].attribute` is rule 2. */
export function ruleIndexOf(field: string): number | null {
  const match = /^rules\[(\d+)\]/.exec(field);
  return match === null ? null : Number(match[1]);
}
