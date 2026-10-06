const C1 = 0xcc9e2d51;
const C2 = 0x1b873593;

/**
 * MurmurHash3, the x86_32 variant, after Austin Appleby's public-domain reference implementation:
 * the variant FR-EVL-002 names, and the one the server implements. Returns the hash as an unsigned
 * integer, the `(h >>> 0)` of FR-EVL-002, so a bucket is never negative.
 *
 * @param length how many bytes of `data` to hash, from the start; all of them by default
 */
export function murmur3X86_32(data: Uint8Array, seed: number, length = data.length): number {
  const blocksEnd = length & ~3;
  let h1 = seed | 0;

  // Body: four bytes at a time, little-endian.
  for (let i = 0; i < blocksEnd; i += 4) {
    const k1 =
      (data[i] as number) |
      ((data[i + 1] as number) << 8) |
      ((data[i + 2] as number) << 16) |
      ((data[i + 3] as number) << 24);
    h1 ^= mixK1(k1);
    h1 = (h1 << 13) | (h1 >>> 19);
    h1 = (Math.imul(h1, 5) + 0xe6546b64) | 0;
  }

  // Tail: the last one to three bytes.
  const tail = length & 3;
  if (tail > 0) {
    let k1 = 0;
    if (tail === 3) {
      k1 ^= (data[blocksEnd + 2] as number) << 16;
    }
    if (tail >= 2) {
      k1 ^= (data[blocksEnd + 1] as number) << 8;
    }
    k1 ^= data[blocksEnd] as number;
    h1 ^= mixK1(k1);
  }

  // Finalisation: force every input bit to affect every output bit.
  h1 ^= length;
  h1 ^= h1 >>> 16;
  h1 = Math.imul(h1, 0x85ebca6b);
  h1 ^= h1 >>> 13;
  h1 = Math.imul(h1, 0xc2b2ae35);
  h1 ^= h1 >>> 16;
  return h1 >>> 0;
}

function mixK1(k1: number): number {
  let k = Math.imul(k1, C1);
  k = (k << 15) | (k >>> 17);
  return Math.imul(k, C2);
}
