// How the audit trail shows an entry (FR-UI-006). Recorded states are shown as recorded (ADR-030):
// field names and values as the entry holds them, never rewritten into today's vocabulary. Where a
// recorded unit differs from the one the rest of the dashboard uses — a rollout is recorded in basis
// points, shown elsewhere as a percentage — the value is shown in its recorded unit, labelled, with
// the percentage beside it only when it is a whole number, so nothing is rounded (ADR-034).

import type { AuditEntryResponse } from "../api/schema";

/** The fixed action vocabulary of docs/DATABASE.md, in words. */
const ACTIONS: Readonly<Record<string, string>> = {
  "project.created": "created the project",
  "environment.created": "created an environment",
  "environment.deleted": "deleted an environment",
  "flag.created": "created a flag",
  "flag.updated": "edited a flag",
  "flag.archived": "archived a flag",
  "flag.restored": "restored a flag",
  "config.updated": "changed a flag's configuration",
  "rules.replaced": "replaced a flag's rules",
  "overrides.replaced": "replaced a flag's user overrides",
  "key.created": "issued an API key",
  "key.revoked": "revoked an API key",
};

export function actionLabel(action: string): string {
  return ACTIONS[action] ?? action;
}

export function actorName(entry: AuditEntryResponse): string {
  return entry.actor.displayName === null ? entry.actor.email : `${entry.actor.displayName} (${entry.actor.email})`;
}

export interface FieldChange {
  readonly field: string;
  readonly before: string | null;
  readonly after: string | null;
}

/**
 * The fields an entry changed, before and after, as text. A creation has no before and a deletion
 * no after; for an edit, only the fields whose recorded value differs are listed.
 */
export function changesOf(entry: AuditEntryResponse): FieldChange[] {
  const before = entry.previousValue;
  const after = entry.newValue;
  const fields = [...new Set([...Object.keys(before ?? {}), ...Object.keys(after ?? {})])];
  return fields
    .filter((field) => before === null || after === null || JSON.stringify(before[field]) !== JSON.stringify(after[field]))
    .map((field) => ({
      field,
      before: before === null ? null : shown(field, before[field]),
      after: after === null ? null : shown(field, after[field]),
    }));
}

/** One recorded value as text. */
export function shown(field: string, value: unknown): string {
  if (field === "rolloutBasisPoints" && typeof value === "number") {
    return basisPoints(value);
  }
  if ((field === "rules" || field === "overrides") && Array.isArray(value)) {
    return value.length === 0 ? "none" : value.map((item) => (field === "rules" ? rule(item) : override(item))).join("; ");
  }
  if (value === null || value === undefined) {
    return "none";
  }
  return typeof value === "string" ? value : JSON.stringify(value);
}

/**
 * A rollout as recorded, in basis points, with the whole percentage it is where it is one. Integer
 * arithmetic only: a value that is not a whole percentage is shown in basis points alone.
 */
export function basisPoints(value: number): string {
  const recorded = `${value} basis points`;
  return Number.isInteger(value) && value % 100 === 0 ? `${recorded} (${value / 100}%)` : recorded;
}

function rule(item: unknown): string {
  const r = item as { attribute?: unknown; operator?: unknown; matchValues?: unknown; resultValue?: unknown };
  return `${String(r.attribute)} ${String(r.operator)} ${JSON.stringify(r.matchValues)} → ${String(r.resultValue)}`;
}

function override(item: unknown): string {
  const o = item as { userKey?: unknown; value?: unknown };
  return `${String(o.userKey)} → ${String(o.value)}`;
}
