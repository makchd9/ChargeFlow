package com.chargeflow.algo.select;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class QuickselectTest {

    @ParameterizedTest(name = "1. random array seed {0}: every k matches sorted order")
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void matchesSortForAllK(long seed) {
        Random r = new Random(seed);
        double[] a = new double[257];
        for (int i = 0; i < a.length; i++) a[i] = r.nextInt(100);  // many duplicates
        double[] s = a.clone();
        Arrays.sort(s);
        RandomizedQuickselect q = new RandomizedQuickselect(seed);
        for (int k = 0; k < a.length; k++) assertEquals(s[k], q.select(a, k), "k=" + k);
    }

    @Test
    @DisplayName("2. boundary: k = 0 (min), k = n-1 (max), single element")
    void boundaries() {
        RandomizedQuickselect q = new RandomizedQuickselect(9);
        double[] a = {5, -2, 9, 0, 3.5};
        assertEquals(-2, q.select(a, 0));
        assertEquals(9, q.select(a, 4));
        assertEquals(9, q.kthLargest(a, 0));
        assertEquals(42, q.select(new double[]{42}, 0));
    }

    @Test
    @DisplayName("3. edge case: all-equal input terminates in linear work (3-way partition)")
    void allEqual() {
        double[] a = new double[100_000];
        Arrays.fill(a, 7.0);
        RandomizedQuickselect q = new RandomizedQuickselect(1);
        assertEquals(7.0, q.select(a, 50_000));
        assertTrue(q.comparisons() <= 3L * a.length, "comparisons " + q.comparisons());
    }

    @Test
    @DisplayName("4. invalid input: empty array, k out of range, NaN")
    void invalid() {
        RandomizedQuickselect q = new RandomizedQuickselect(1);
        assertThrows(IllegalArgumentException.class, () -> q.select(new double[0], 0));
        assertThrows(IllegalArgumentException.class, () -> q.select(null, 0));
        assertThrows(IllegalArgumentException.class, () -> q.select(new double[]{1, 2}, 2));
        assertThrows(IllegalArgumentException.class, () -> q.select(new double[]{1, 2}, -1));
        assertThrows(IllegalArgumentException.class, () -> q.select(new double[]{1, Double.NaN}, 0));
        assertThrows(IllegalArgumentException.class, () -> q.percentile(new double[]{1}, 101));
    }

    @Test
    @DisplayName("5. sorted input: randomized stays near-linear while the first-pivot baseline goes quadratic")
    void sortedInputAdversary() {
        int n = 20_000;
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = i;
        RandomizedQuickselect rq = new RandomizedQuickselect(3);
        NaiveQuickselect nq = new NaiveQuickselect();
        assertEquals(n / 2, rq.select(a, n / 2));
        assertEquals(n / 2, nq.select(a, n / 2));
        assertTrue(rq.comparisons() < 10L * n, "randomized: " + rq.comparisons());
        assertTrue(nq.comparisons() > (long) n * n / 10, "naive: " + nq.comparisons());
    }

    @Test
    @DisplayName("6. input array is not modified by select(); percentile uses the nearest rank")
    void nonDestructiveAndPercentile() {
        double[] a = {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
        double[] copy = a.clone();
        RandomizedQuickselect q = new RandomizedQuickselect(5);
        assertEquals(50, q.percentile(a, 50));
        assertEquals(90, q.percentile(a, 90));
        assertEquals(10, q.percentile(a, 0));
        assertEquals(100, q.percentile(a, 100));
        assertArrayEquals(copy, a);
    }

    @Test
    @DisplayName("7. expected linear work: comparisons / n stays bounded as n grows 100x")
    void expectedLinear() {
        double ratioSmall = work(10_000), ratioLarge = work(1_000_000);
        assertTrue(ratioLarge < 2 * ratioSmall + 2, "small " + ratioSmall + " large " + ratioLarge);
    }

    private static double work(int n) {
        Random r = new Random(n);
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = r.nextDouble();
        long total = 0;
        for (int s = 0; s < 5; s++) {
            RandomizedQuickselect q = new RandomizedQuickselect(s);
            q.select(a, n / 2);
            total += q.comparisons();
        }
        return total / 5.0 / n;
    }
}
