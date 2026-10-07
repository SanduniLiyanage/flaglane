// What the key management page says and orders (FR-UI-005). Kept apart from the page so that its
// claims about revocation and key types are tested against what the API and the SDK actually do.

import type { ApiKeyResponse } from "../api/schema";

export type KeyType = ApiKeyResponse["type"];

/** What each key type reads (FR-KEY-004, FR-KEY-005, FR-KEY-008). */
export const KEY_TYPES: Readonly<Record<KeyType, { readonly label: string; readonly reads: string }>> = {
  server: {
    label: "Server",
    reads: "Reads every flag in the environment, with its user overrides. For backend services; keep it secret.",
  },
  client: {
    label: "Client",
    reads:
      "Reads client-side-visible flags only, with no user overrides. Safe to ship in a browser or a mobile app, where anyone can read it: treat it as public.",
  },
};

/**
 * What revoking does, said before it is done. Flaglane refuses the key on every request from the
 * moment the revoke returns (FR-KEY-003). An SDK refused keeps the last ruleset it downloaded and
 * keeps retrying, so an application already running carries on as it was, without further changes;
 * one started afterwards never gets a ruleset and answers with the defaults in its code.
 */
export function revokeWarning(name: string, environment: string): string {
  return (
    `Revoke ${name}? Flaglane refuses it on every request from the moment this returns. Applications ` +
    `already running with it keep evaluating the ${environment} ruleset they last downloaded, but get no ` +
    "further changes, and an application started with it afterwards gets none at all and answers with " +
    "the defaults in its code. A revoked key cannot be restored."
  );
}

/** Live keys first, newest first within each. */
export function ordered(keys: readonly ApiKeyResponse[]): ApiKeyResponse[] {
  return [...keys].sort((a, b) => {
    const revoked = Number(a.revokedAt !== null) - Number(b.revokedAt !== null);
    return revoked !== 0 ? revoked : b.createdAt.localeCompare(a.createdAt);
  });
}
