import { type CompiledRule, type ServedRule, compileRule } from "./rules.js";

/**
 * One flag ready to evaluate. A flag whose served fields are not what the server sends is kept as
 * broken rather than dropped, so evaluating it is an `ERROR` that says so instead of a
 * `FLAG_NOT_FOUND` that would hide it.
 */
export type CompiledFlag =
  | {
      readonly key: string;
      readonly broken: false;
      readonly enabled: boolean;
      readonly fallthroughValue: boolean;
      readonly rolloutBasisPoints: number;
      readonly rolloutSalt: string;
      readonly overrides: ReadonlyMap<string, boolean>;
      readonly rules: readonly CompiledRule[];
    }
  | { readonly key: string; readonly broken: true; readonly problem: string };

/** An environment's ruleset, parsed once when it arrives and immutable after. */
export interface Ruleset {
  readonly environment: string;
  readonly version: number;
  readonly flags: ReadonlyMap<string, CompiledFlag>;
}

/**
 * Reads a ruleset in the shape `GET /sdk/config` serves (docs/API.md). Returns undefined for
 * anything that is not a ruleset at all, so a bad response never replaces a good ruleset. Rules are
 * compiled here, once, and kept in priority order; the sort is stable, as the server's is.
 *
 * A client key's ruleset carries no `overrides` field (FR-KEY-008); a flag without one simply has
 * no overrides.
 */
export function parseRuleset(served: unknown): Ruleset | undefined {
  if (!isRecord(served)) {
    return undefined;
  }
  const { environment, version, flags } = served;
  if (typeof environment !== "string" || typeof version !== "number" || !Array.isArray(flags)) {
    return undefined;
  }
  const compiled = new Map<string, CompiledFlag>();
  for (const flag of flags) {
    if (isRecord(flag) && typeof flag["key"] === "string" && !compiled.has(flag["key"])) {
      compiled.set(flag["key"], compileFlag(flag["key"], flag));
    }
  }
  return { environment, version, flags: compiled };
}

function compileFlag(key: string, flag: Record<string, unknown>): CompiledFlag {
  const { enabled, fallthroughValue, rolloutBasisPoints, rolloutSalt, overrides, rules } = flag;
  if (
    typeof enabled !== "boolean" ||
    typeof fallthroughValue !== "boolean" ||
    typeof rolloutSalt !== "string" ||
    !Number.isInteger(rolloutBasisPoints) ||
    !Array.isArray(rules) ||
    (overrides !== undefined && !Array.isArray(overrides))
  ) {
    return { key, broken: true, problem: "the served configuration is not well formed" };
  }
  const byUser = new Map<string, boolean>();
  for (const override of (overrides ?? []) as unknown[]) {
    if (
      isRecord(override) &&
      typeof override["userKey"] === "string" &&
      typeof override["value"] === "boolean"
    ) {
      byUser.set(override["userKey"], override["value"]);
    }
  }
  const compiledRules = (rules as unknown[])
    .map((rule) => compileRule((isRecord(rule) ? rule : {}) as unknown as ServedRule))
    .sort((a, b) => a.priority - b.priority);
  return {
    key,
    broken: false,
    enabled,
    fallthroughValue,
    rolloutBasisPoints: rolloutBasisPoints as number,
    rolloutSalt,
    overrides: byUser,
    rules: compiledRules,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
