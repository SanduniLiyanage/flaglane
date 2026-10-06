import { murmur3X86_32 } from "./murmur3.js";

/** Buckets run 0 to 9999, so a rollout counts in basis points (ADR-011). */
export const BUCKET_COUNT = 10_000;

/**
 * Where a key is encoded before it is hashed, reused by every call: evaluation sits on the
 * application's request path, and allocating a fresh array per evaluation was most of its cost.
 * Grown when a longer key arrives, never shrunk.
 */
let scratch = new Uint8Array(256);

/**
 * The user's bucket for this salt, exactly as the server computes it (FR-EVL-002):
 *
 * ```
 * murmur3_x86_32(utf8(rolloutSalt + ":" + userKey), seed = 0) unsigned % 10000
 * ```
 *
 * An unpaired surrogate is encoded as U+FFFD, as `TextEncoder` writes it and as the server does on
 * purpose, so even a malformed key lands in one bucket on both sides (ADR-018).
 */
export function bucket(rolloutSalt: string, userKey: string): number {
  const worst = (rolloutSalt.length + 1 + userKey.length) * 3;
  if (scratch.length < worst) {
    scratch = new Uint8Array(Math.max(worst, scratch.length * 2));
  }
  let length = encodeUtf8(rolloutSalt, scratch, 0);
  scratch[length++] = 0x3a; // ":"
  length = encodeUtf8(userKey, scratch, length);
  return murmur3X86_32(scratch, 0, length) % BUCKET_COUNT;
}

/** Strictly less than: 0 reaches nobody, 10000 everybody, and raising it only adds users. */
export function isInRollout(rolloutSalt: string, userKey: string, rolloutBasisPoints: number): boolean {
  return bucket(rolloutSalt, userKey) < rolloutBasisPoints;
}

/**
 * Writes `text` as UTF-8 into `into` from `at`, returning where it ended. Byte for byte what
 * `TextEncoder` produces, unpaired surrogates included: each becomes U+FFFD. `into` must have room
 * for three bytes per UTF-16 code unit, the most any code unit needs.
 */
export function encodeUtf8(text: string, into: Uint8Array, at: number): number {
  let n = at;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c < 0x80) {
      into[n++] = c;
    } else if (c < 0x800) {
      into[n++] = 0xc0 | (c >> 6);
      into[n++] = 0x80 | (c & 0x3f);
    } else if (c < 0xd800 || c > 0xdfff) {
      into[n++] = 0xe0 | (c >> 12);
      into[n++] = 0x80 | ((c >> 6) & 0x3f);
      into[n++] = 0x80 | (c & 0x3f);
    } else {
      const low = i + 1 < text.length ? text.charCodeAt(i + 1) : 0;
      if (c <= 0xdbff && low >= 0xdc00 && low <= 0xdfff) {
        const point = 0x10000 + ((c - 0xd800) << 10) + (low - 0xdc00);
        into[n++] = 0xf0 | (point >> 18);
        into[n++] = 0x80 | ((point >> 12) & 0x3f);
        into[n++] = 0x80 | ((point >> 6) & 0x3f);
        into[n++] = 0x80 | (point & 0x3f);
        i++;
      } else {
        into[n++] = 0xef;
        into[n++] = 0xbf;
        into[n++] = 0xbd;
      }
    }
  }
  return n;
}
