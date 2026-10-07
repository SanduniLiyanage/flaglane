// The override list's staged set (FR-UI-004, FR-RUL-001): one value per user key, replaced as a
// whole. The API's checks — a user key present, at most 256 characters, each key once, at most
// 1,000 overrides (ADR-023) — are made here as the list is edited.

import type { OverrideRequest } from "../api/schema";

export const MAX_OVERRIDES = 1000;
export const MAX_USER_KEY = 256;

export interface OverrideDraft {
  /** Identifies the row while the list is edited; never sent. */
  readonly id: number;
  readonly userKey: string;
  readonly value: boolean;
}

let nextId = 1;

export function newOverride(): OverrideDraft {
  return { id: nextId++, userKey: "", value: true };
}

export function draftsFrom(overrides: readonly OverrideRequest[]): OverrideDraft[] {
  return overrides.map((override) => ({ id: nextId++, userKey: override.userKey, value: override.value }));
}

/** What is wrong with each entry, by its id; an entry with nothing wrong is absent. */
export function problemsOf(drafts: readonly OverrideDraft[]): ReadonlyMap<number, string> {
  const problems = new Map<number, string>();
  const seen = new Set<string>();
  for (const draft of drafts) {
    if (draft.userKey.trim() === "") {
      problems.set(draft.id, "Enter the user key, as your application sends it.");
    } else if (draft.userKey.length > MAX_USER_KEY) {
      problems.set(draft.id, `A user key is at most ${MAX_USER_KEY} characters.`);
    } else if (seen.has(draft.userKey)) {
      problems.set(draft.id, "This user already has an override above. Each user key appears once.");
    }
    seen.add(draft.userKey);
  }
  return problems;
}

/** The set as the API takes it. Only for a list without problems. */
export function toRequests(drafts: readonly OverrideDraft[]): OverrideRequest[] {
  return drafts.map((draft) => ({ userKey: draft.userKey, value: draft.value }));
}

/**
 * Whether the staged set differs from the saved one. Overrides are a set, so order does not count;
 * a list with a problem always counts as a change.
 */
export function overridesChanged(saved: readonly OverrideRequest[], drafts: readonly OverrideDraft[]): boolean {
  if (problemsOf(drafts).size > 0 || saved.length !== drafts.length) {
    return true;
  }
  const byKey = new Map(saved.map((override) => [override.userKey, override.value]));
  return drafts.some((draft) => byKey.get(draft.userKey) !== draft.value);
}
