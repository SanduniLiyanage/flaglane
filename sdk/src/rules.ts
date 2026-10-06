import { sameValue, toValue } from "./context.js";
import type { User, Value } from "./types.js";

/** A targeting rule as `GET /sdk/config` serves it. Typed loosely: it came over the network. */
export interface ServedRule {
  priority: unknown;
  attribute: unknown;
  operator: unknown;
  matchValues: unknown;
  resultValue: unknown;
}

export interface CompiledRule {
  readonly priority: number;
  readonly resultValue: boolean;
  /** Why the rule cannot be applied, or undefined when it can. */
  readonly malformation: string | undefined;
  /** Whether the user matches. Only meaningful for a rule that is not malformed. */
  matches(user: User): boolean;
}

type Operator =
  | "EQUALS"
  | "NOT_EQUALS"
  | "IN"
  | "NOT_IN"
  | "CONTAINS"
  | "STARTS_WITH"
  | "ENDS_WITH";

/** How many match values each operator takes (ADR-019): exactly one, or one or more of one type. */
const SHAPES: Readonly<Record<Operator, "single" | "list">> = {
  EQUALS: "single",
  NOT_EQUALS: "single",
  IN: "list",
  NOT_IN: "list",
  CONTAINS: "single",
  STARTS_WITH: "single",
  ENDS_WITH: "single",
};

function isOperator(name: unknown): name is Operator {
  return typeof name === "string" && Object.hasOwn(SHAPES, name);
}

/**
 * Checks a rule once, when its ruleset is loaded, and returns it ready to match. The checks and
 * the semantics are the server's (FR-RUL-006 to FR-RUL-009, ADR-019), in the same order, so a rule
 * one implementation calls malformed the other does too:
 *
 * - an absent attribute never matches, for every operator, the negative ones included;
 * - equality is type-strict, case-sensitive, with no Unicode normalisation;
 * - for a present attribute, NOT_EQUALS and NOT_IN are exactly the negations of EQUALS and IN;
 * - CONTAINS, STARTS_WITH and ENDS_WITH need a string on both sides and otherwise never match.
 */
export function compileRule(rule: ServedRule): CompiledRule {
  const priority = typeof rule.priority === "number" ? rule.priority : 0;
  const resultValue = rule.resultValue === true;
  const malformed = (problem: string): CompiledRule => ({
    priority,
    resultValue,
    malformation: problem,
    matches: () => false,
  });

  const attribute = rule.attribute;
  if (typeof attribute !== "string" || attribute === "") {
    return malformed("rule names no attribute");
  }
  const operator = rule.operator;
  if (!isOperator(operator)) {
    return malformed(`unknown operator ${String(operator)}`);
  }
  const raw = rule.matchValues;
  if (!Array.isArray(raw) || raw.length === 0) {
    return malformed("rule has no match values");
  }
  if (SHAPES[operator] === "single" && raw.length !== 1) {
    return malformed(`${operator} takes exactly one match value, has ${raw.length}`);
  }
  const values: Value[] = [];
  for (let i = 0; i < raw.length; i++) {
    const value = toValue(raw[i]);
    if (value === undefined) {
      return malformed(`match value ${i} is not a string, a number or a boolean`);
    }
    values.push(value);
  }
  if (typeof rule.resultValue !== "boolean") {
    return malformed("rule has no result value");
  }

  if (SHAPES[operator] === "list") {
    const type = (values[0] as Value).type;
    if (values.some((value) => value.type !== type)) {
      return malformed(`${operator} match values are not all of one type`);
    }
    const members = new Set(values.map((value) => value.value));
    const contains = (actual: Value) => actual.type === type && members.has(actual.value);
    return compiled(priority, resultValue, attribute, (actual) =>
      operator === "IN" ? contains(actual) : !contains(actual),
    );
  }

  const operand = values[0] as Value;
  return compiled(priority, resultValue, attribute, (actual) => {
    switch (operator) {
      case "EQUALS":
        return sameValue(actual, operand);
      case "NOT_EQUALS":
        return !sameValue(actual, operand);
      case "CONTAINS":
        return strings(actual, operand, (text, part) => text.includes(part));
      case "STARTS_WITH":
        return strings(actual, operand, (text, part) => text.startsWith(part));
      case "ENDS_WITH":
        return strings(actual, operand, (text, part) => text.endsWith(part));
      default:
        return false;
    }
  });
}

function compiled(
  priority: number,
  resultValue: boolean,
  attribute: string,
  test: (actual: Value) => boolean,
): CompiledRule {
  return {
    priority,
    resultValue,
    malformation: undefined,
    matches(user) {
      const actual = user.attributes.get(attribute);
      return actual !== undefined && test(actual);
    },
  };
}

/**
 * Applies a string operator when both sides are strings, and never matches otherwise. String
 * operators compare UTF-16 code units, as Java's do; for well-formed strings that is the same
 * answer as comparing UTF-8 bytes.
 */
function strings(
  actual: Value,
  operand: Value,
  test: (text: string, part: string) => boolean,
): boolean {
  return actual.type === "string" && operand.type === "string" && test(actual.value, operand.value);
}
