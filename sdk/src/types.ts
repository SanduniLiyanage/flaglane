/** Why an evaluation produced its value: the step of the resolution order that decided it. */
export type Reason =
  | "OFF"
  | "OVERRIDE"
  | "RULE_MATCH"
  | "ROLLOUT"
  | "FALLTHROUGH"
  | "FLAG_NOT_FOUND"
  | "ERROR";

export interface Evaluation {
  readonly value: boolean;
  readonly reason: Reason;
}

/**
 * A user attribute or rule operand: a string, a finite number or a boolean, and nothing else.
 * Compared type-strictly: the number 1 never equals the string "1" (FR-RUL-006, FR-RUL-007).
 */
export type Value =
  | { readonly type: "string"; readonly value: string }
  | { readonly type: "number"; readonly value: number }
  | { readonly type: "boolean"; readonly value: boolean };

/** Who a flag is evaluated for, as the engine sees it. */
export interface User {
  /** Absent for an anonymous evaluation; an empty key counts as absent (ADR-017). */
  readonly key: string | undefined;
  readonly attributes: ReadonlyMap<string, Value>;
}

/** Where the SDK reports problems. It never throws through it. */
export interface Logger {
  warn(message: string, error?: unknown): void;
}
