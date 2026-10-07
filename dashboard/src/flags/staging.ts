// The flag detail form's staged edits (FR-UI-007, ADR-031). The fallthrough value and the rollout
// are edited locally and saved together with one PATCH carrying only what changed; nothing reaches
// an SDK until Save. The kill switch is not part of this: it saves at once.

import type { FlagConfigResponse, UpdateFlagConfigRequest } from "../api/schema";

export interface Staged {
  readonly fallthroughValue: boolean;
  /** A whole percentage, 0 to 100 (ADR-011). */
  readonly rolloutPercentage: number;
}

export function stagedFrom(config: FlagConfigResponse): Staged {
  return { fallthroughValue: config.fallthroughValue, rolloutPercentage: config.rolloutPercentage };
}

/** The fields of `staged` that differ from `saved`: the body of the PATCH that saves them. */
export function changes(saved: FlagConfigResponse, staged: Staged): UpdateFlagConfigRequest {
  const body: { fallthroughValue?: boolean; rolloutPercentage?: number } = {};
  if (staged.fallthroughValue !== saved.fallthroughValue) {
    body.fallthroughValue = staged.fallthroughValue;
  }
  if (staged.rolloutPercentage !== saved.rolloutPercentage) {
    body.rolloutPercentage = staged.rolloutPercentage;
  }
  return body;
}

export function isDirty(saved: FlagConfigResponse, staged: Staged): boolean {
  return Object.keys(changes(saved, staged)).length > 0;
}

/**
 * Whether the rollout changes anything. While the fallthrough value is true, everyone the rollout
 * does not reach gets true anyway (ADR-009), so the control is disabled and says why.
 */
export function rolloutIsInert(staged: Staged): boolean {
  return staged.fallthroughValue;
}

/**
 * The whole percentage a range input's value stands for. The input steps by 1 from 0 to 100, so
 * anything else did not come from it and is refused rather than rounded.
 */
export function percentageFrom(value: string): number {
  // Digits only: Number("") is 0, and an empty value must not become a 0% rollout.
  const parsed = /^\d{1,3}$/.test(value) ? Number(value) : Number.NaN;
  if (!Number.isInteger(parsed) || parsed < 0 || parsed > 100) {
    throw new RangeError(`A rollout is a whole percentage from 0 to 100, not ${value}`);
  }
  return parsed;
}
