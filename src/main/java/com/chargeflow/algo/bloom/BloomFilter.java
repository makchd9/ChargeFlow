package com.chargeflow.algo.bloom;

import java.nio.charset.StandardCharsets;

/**
 * Bloom filter over strings, backed by a hand-managed {@code long[]} bit array.
 *
 * <p>{@link #mightContain(String)} returning {@code false} means the key was
 * <b>definitely never added</b> (no false negatives). {@code true} means
 * <b>possibly present</b>: callers that need exact membership must confirm the
 * hit against an authoritative store (see RegistryService).</p>
 *
 * <p><b>Sizing</b> for n expected keys and target false-positive rate p:
 * <pre>
 *   m = ceil( -n ln p / (ln 2)^2 )       bits
 *   k = max(1, round( (m / n) ln 2 ))   hash functions
 *   FPR(n) ≈ (1 - e^(-k n / m))^k
 * </pre>
 *
 * <p><b>Hashing:</b> two independent 64-bit hashes h1, h2 (own implementations:
 * FNV-1a and a seeded multiplicative hash, each passed through the MurmurHash3
 * 64-bit finaliser for avalanche). The k probe positions come from
 * Kirsch–Mitzenmacher double hashing: g_i(x) = h1(x) + i * h2(x) mod m. This
 * keeps the asymptotic FPR of k independent hashes at the cost of two hash
 * computations.</p>
 *
 * <p>Time: O(k + |key|) per insert or query. Space: m bits, independent of key length.</p>
 */
public final class BloomFilter {

    private static final double LN2 = Math.log(2);

    private final long[] words;
    private final long bitCount;     // m
    private final int hashCount;     // k
    private final long expected;     // n used for sizing
    private final double targetFpr;  // p used for sizing
    private long insertions;         // add() calls (duplicates included)
    private long bitsSet;            // population count, maintained incrementally

    public BloomFilter(long bitCount, int hashCount) {
        if (bitCount <= 0) throw new IllegalArgumentException("bitCount must be > 0");
        if (hashCount <= 0 || hashCount > 64) throw new IllegalArgumentException("hashCount must be in 1..64");
        long wordCount = (bitCount + 63) >>> 6;
        if (wordCount > Integer.MAX_VALUE - 8) throw new IllegalArgumentException("filter too large");
        this.words = new long[(int) wordCount];
        this.bitCount = bitCount;
        this.hashCount = hashCount;
        this.expected = -1;
        this.targetFpr = Double.NaN;
    }

    private BloomFilter(long bitCount, int hashCount, long expected, double targetFpr) {
        long wordCount = (bitCount + 63) >>> 6;
        if (wordCount > Integer.MAX_VALUE - 8) throw new IllegalArgumentException("filter too large");
        this.words = new long[(int) wordCount];
        this.bitCount = bitCount;
        this.hashCount = hashCount;
        this.expected = expected;
        this.targetFpr = targetFpr;
    }

    /** Creates an optimally sized filter for {@code expectedInsertions} keys at false-positive rate {@code fpr}. */
    public static BloomFilter optimal(long expectedInsertions, double fpr) {
        if (expectedInsertions <= 0) throw new IllegalArgumentException("expectedInsertions must be > 0");
        if (!(fpr > 0.0 && fpr < 1.0)) throw new IllegalArgumentException("false-positive rate must be in (0,1)");
        long m = optimalBits(expectedInsertions, fpr);
        int k = optimalHashes(expectedInsertions, m);
        return new BloomFilter(m, k, expectedInsertions, fpr);
    }

    public static long optimalBits(long n, double p) {
        return Math.max(64, (long) Math.ceil(-n * Math.log(p) / (LN2 * LN2)));
    }

    public static int optimalHashes(long n, long m) {
        return (int) Math.max(1, Math.min(64, Math.round((double) m / n * LN2)));
    }

    /** Theoretical FPR after inserting n distinct keys into m bits with k hashes. */
    public static double theoreticalFpr(long n, long m, int k) {
        return Math.pow(1 - Math.exp(-(double) k * n / m), k);
    }

    public void add(String key) {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        byte[] b = key.getBytes(StandardCharsets.UTF_8);
        long h1 = hash1(b), h2 = hash2(b) | 1L; // force a non-zero step so the k probes are distinct
        for (int i = 0; i < hashCount; i++) {
            long idx = Long.remainderUnsigned(h1 + i * h2, bitCount);
            int w = (int) (idx >>> 6);
            long mask = 1L << (idx & 63);
            if ((words[w] & mask) == 0) {
                words[w] |= mask;
                bitsSet++;
            }
        }
        insertions++;
    }

    public boolean mightContain(String key) {
        if (key == null) return false;
        byte[] b = key.getBytes(StandardCharsets.UTF_8);
        long h1 = hash1(b), h2 = hash2(b) | 1L;
        for (int i = 0; i < hashCount; i++) {
            long idx = Long.remainderUnsigned(h1 + i * h2, bitCount);
            if ((words[(int) (idx >>> 6)] & (1L << (idx & 63))) == 0) return false; // definitely absent
        }
        return true; // possibly present
    }

    // ---- hash functions (own implementations) -------------------------------------

    /** FNV-1a 64-bit, then MurmurHash3 fmix64 finaliser. */
    static long hash1(byte[] data) {
        long h = 0xcbf29ce484222325L;
        for (byte x : data) {
            h ^= (x & 0xff);
            h *= 0x100000001b3L;
        }
        return fmix64(h);
    }

    /** Seeded multiplicative (polynomial) hash with a different seed and multiplier, then fmix64. */
    static long hash2(byte[] data) {
        long h = 0x9E3779B97F4A7C15L ^ data.length;
        for (byte x : data) {
            h = (h ^ (x & 0xff)) * 0xC2B2AE3D27D4EB4FL;
            h = Long.rotateLeft(h, 31);
        }
        return fmix64(h);
    }

    static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    // ---- statistics -------------------------------------------------------------

    public long bitCount() { return bitCount; }
    public int hashCount() { return hashCount; }
    public long insertions() { return insertions; }
    public long bitsSet() { return bitsSet; }
    public long expectedInsertions() { return expected; }
    public double targetFpr() { return targetFpr; }
    public double fillRatio() { return (double) bitsSet / bitCount; }
    /** FPR estimated from the actual fill ratio: (bitsSet/m)^k. */
    public double estimatedFprFromFill() { return Math.pow(fillRatio(), hashCount); }
    public long memoryBytes() { return (long) words.length * 8L; }
}
