package io.github.sanduniliyanage.flaglane.evaluation;

/**
 * MurmurHash3, the x86_32 variant, after Austin Appleby's public-domain reference implementation.
 *
 * <p>Implemented here rather than taken from a library because FR-EVL-002 pins the exact variant,
 * and it is about forty lines. The variant matters: x64_128 truncated to 32 bits is a different
 * function, and so is Guava's deprecated {@code murmur3_32()} on non-ASCII input.
 */
final class MurmurHash3 {

  private static final int C1 = 0xcc9e2d51;
  private static final int C2 = 0x1b873593;

  private MurmurHash3() {}

  /** The 32-bit hash of {@code data}. Callers wanting a non-negative number must widen unsigned. */
  static int x86_32(byte[] data, int seed) {
    int length = data.length;
    int blocksEnd = length & ~3;
    int h1 = seed;

    // Body: four bytes at a time, little-endian.
    for (int i = 0; i < blocksEnd; i += 4) {
      int k1 =
          (data[i] & 0xff)
              | (data[i + 1] & 0xff) << 8
              | (data[i + 2] & 0xff) << 16
              | (data[i + 3] & 0xff) << 24;
      h1 ^= mixK1(k1);
      h1 = Integer.rotateLeft(h1, 13);
      h1 = h1 * 5 + 0xe6546b64;
    }

    // Tail: the last one to three bytes. Written without switch fallthrough, which the reference
    // relies on and which static analysis rightly treats as a bug everywhere else.
    int tail = length & 3;
    if (tail > 0) {
      int k1 = 0;
      if (tail == 3) {
        k1 ^= (data[blocksEnd + 2] & 0xff) << 16;
      }
      if (tail >= 2) {
        k1 ^= (data[blocksEnd + 1] & 0xff) << 8;
      }
      k1 ^= data[blocksEnd] & 0xff;
      h1 ^= mixK1(k1);
    }

    // Finalisation: force every input bit to affect every output bit.
    h1 ^= length;
    h1 ^= h1 >>> 16;
    h1 *= 0x85ebca6b;
    h1 ^= h1 >>> 13;
    h1 *= 0xc2b2ae35;
    h1 ^= h1 >>> 16;
    return h1;
  }

  private static int mixK1(int k1) {
    k1 *= C1;
    k1 = Integer.rotateLeft(k1, 15);
    k1 *= C2;
    return k1;
  }
}
