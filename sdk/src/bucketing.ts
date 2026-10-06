import { murmur3X86_32 } from "./murmur3.js";

/** Buckets run 0 to 9999, so a rollout counts in basis points (ADR-011). */
export const BUCKET_COUNT = 10_000;

const encoder = new TextEncoder();

/**
 * The user's bucket for this salt, exactly as the server computes it (FR-EVL-002):
 *
 * ```
 * murmur3_x86_32(utf8(rolloutSalt + ":" + userKey), seed = 0) unsigned % 10000
 * ```
 *
 * `TextEncoder` writes an unpaired surrogate as U+FFFD, and the server does the same on purpose,
 * so even a malformed key lands in one bucket on both sides (ADR-018).
 */
export function bucket(rolloutSalt: string, userKey: string): number {
  return murmur3X86_32(encoder.encode(`${rolloutSalt}:${userKey}`), 0) % BUCKET_COUNT;
}

/** Strictly less than: 0 reaches nobody, 10000 everybody, and raising it only adds users. */
export function isInRollout(rolloutSalt: string, userKey: string, rolloutBasisPoints: number): boolean {
  return bucket(rolloutSalt, userKey) < rolloutBasisPoints;
}
