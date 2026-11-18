package com.chargeflow.algo.bloom;

import com.chargeflow.data.SyntheticData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BloomFilterTest {

    @Test
    @DisplayName("1. no false negatives: every inserted key is reported as possibly present")
    void noFalseNegatives() {
        List<String> keys = SyntheticData.plates(20_000, new Random(1), Set.of());
        BloomFilter bf = BloomFilter.optimal(keys.size(), 0.01);
        keys.forEach(bf::add);
        for (String k : keys) assertTrue(bf.mightContain(k), k);
    }

    @Test
    @DisplayName("2. edge case: an empty filter contains nothing, and a null key is not contained")
    void emptyFilter() {
        BloomFilter bf = BloomFilter.optimal(100, 0.01);
        assertFalse(bf.mightContain("MH12AB1234"));
        assertFalse(bf.mightContain(""));
        assertFalse(bf.mightContain(null));
        assertEquals(0, bf.bitsSet());
    }

    @Test
    @DisplayName("3. sizing matches m = -n ln p / (ln2)^2 and k = (m/n) ln 2")
    void sizingFormula() {
        BloomFilter bf = BloomFilter.optimal(1_000_000, 0.01);
        assertEquals(9_585_059, bf.bitCount());          // ceil(1e6 * 9.585058...)
        assertEquals(7, bf.hashCount());                 // round(9.585 * 0.693) = 7
        assertEquals(0.01, BloomFilter.theoreticalFpr(1_000_000, bf.bitCount(), bf.hashCount()), 0.0005);
    }

    @Test
    @DisplayName("4. probabilistic: empirical FPR on 200k absent keys stays within 25% of theory")
    void empiricalFpr() {
        int n = 50_000;
        List<String> keys = SyntheticData.plates(n, new Random(2), Set.of());
        List<String> absent = SyntheticData.plates(200_000, new Random(3), new HashSet<>(keys));
        for (double p : new double[]{0.05, 0.01}) {
            BloomFilter bf = BloomFilter.optimal(n, p);
            keys.forEach(bf::add);
            long fp = absent.stream().filter(bf::mightContain).count();
            double emp = fp / 200_000.0;
            double th = BloomFilter.theoreticalFpr(n, bf.bitCount(), bf.hashCount());
            assertTrue(Math.abs(emp - th) / th < 0.25, "p=" + p + " empirical " + emp + " vs theory " + th);
            assertEquals(th, bf.estimatedFprFromFill(), th * 0.1, "fill-ratio estimate agrees with theory");
        }
    }

    @Test
    @DisplayName("5. invalid parameters are rejected")
    void invalidParams() {
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.optimal(0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.optimal(10, 0));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.optimal(10, 1));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(0, 3));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(64, 0));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.optimal(10, 0.01).add(null));
    }

    @Test
    @DisplayName("6. duplicate inserts are idempotent on the bit array")
    void duplicates() {
        BloomFilter bf = BloomFilter.optimal(1000, 0.01);
        bf.add("KA05MN4321");
        long bits = bf.bitsSet();
        bf.add("KA05MN4321");
        assertEquals(bits, bf.bitsSet());
        assertEquals(2, bf.insertions());
        assertTrue(bits <= bf.hashCount());
    }

    @Test
    @DisplayName("7. overfilling beyond the design capacity raises the FPR (expected behaviour)")
    void overfill() {
        int n = 2_000;
        BloomFilter bf = BloomFilter.optimal(n, 0.01);
        SyntheticData.plates(n * 4, new Random(4), Set.of()).forEach(bf::add);
        assertTrue(bf.estimatedFprFromFill() > 0.1, "4x overload must degrade the filter");
    }
}
