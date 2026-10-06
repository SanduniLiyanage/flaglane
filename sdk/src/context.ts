import type { User, Value } from "./types.js";

/**
 * A value the engine can compare: a string, a finite number or a boolean. Anything else — null,
 * an array, an object, NaN — is not a value and is ignored, as the server ignores it (FR-RUL-006).
 */
export function toValue(raw: unknown): Value | undefined {
  switch (typeof raw) {
    case "string":
      return { type: "string", value: raw };
    case "number":
      return Number.isFinite(raw) ? { type: "number", value: raw } : undefined;
    case "boolean":
      return { type: "boolean", value: raw };
    default:
      return undefined;
  }
}

/**
 * Type-strict equality (FR-RUL-007). Numbers compare as JavaScript numbers always do, which is how
 * the server compares them too: 1 equals 1.0, -0 equals 0 (ADR-017).
 */
export function sameValue(a: Value, b: Value): boolean {
  return a.type === b.type && a.value === b.value;
}

/**
 * Builds the engine's view of a user from the flat context an application passes: `key`, and
 * every other own property as an attribute. As `userFrom(key, attributes)` would build it, without
 * first copying the context into a key and a separate record: this runs on every evaluation.
 */
export function userFromContext(context: Readonly<Record<string, unknown>>): User {
  const values = new Map<string, Value>();
  for (const name in context) {
    if (name !== "key" && Object.hasOwn(context, name)) {
      const value = toValue(context[name]);
      if (value !== undefined) {
        values.set(name, value);
      }
    }
  }
  const key = context.key;
  return { key: typeof key === "string" && key !== "" ? key : undefined, attributes: values };
}

/**
 * Builds the engine's view of a user from a key and an attribute record. An empty key is no key
 * (ADR-017); an attribute that is not a string, finite number or boolean is dropped.
 */
export function userFrom(key: unknown, attributes: unknown): User {
  const values = new Map<string, Value>();
  if (attributes !== null && typeof attributes === "object" && !Array.isArray(attributes)) {
    for (const [name, raw] of Object.entries(attributes)) {
      const value = toValue(raw);
      if (value !== undefined) {
        values.set(name, value);
      }
    }
  }
  return { key: typeof key === "string" && key !== "" ? key : undefined, attributes: values };
}
