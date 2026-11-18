package com.chargeflow.algo.select;

import java.util.Random;

/**
 * Randomized Quickselect (Hoare's FIND with a uniformly random pivot) using a
 * three-way (Dutch national flag) partition so that inputs with many equal keys,
 * such as tied priority scores, do not degrade to quadratic time.
 *
 * <pre>
 * SELECT(A, lo, hi, k):                 // k-th smallest, 0-based, lo <= k <= hi
 *   loop:
 *     if lo == hi: return A[lo]
 *     p = A[random index in lo..hi]
 *     (lt, gt) = 3-way-partition(A, lo, hi, p)  // A[lo..lt-1] < p, A[lt..gt] == p, A[gt+1..hi] > p
 *     if k < lt:      hi = lt - 1
 *     else if k > gt: lo = gt + 1
 *     else:           return p
 * </pre>
 *
 * <p><b>Expected time O(n)</b>: with a random pivot the expected size of the
 * surviving side is at most 3n/4 after a "good" pivot, which happens with
 * probability 1/2, so E[T(n)] <= T(3n/4) + O(n) = O(n). Worst case O(n^2), with
 * probability vanishing in n. Extra space is O(1) (iterative, in place).</p>
 *
 * <p>The operation counter {@link #comparisons()} records element comparisons so
 * benchmarks can report machine-independent work alongside wall time.</p>
 */
public final class RandomizedQuickselect {

    private final Random rng;
    private long comparisons;

    public RandomizedQuickselect(long seed) { this.rng = new Random(seed); }
    public RandomizedQuickselect(Random rng) { this.rng = rng; }

    /**
     * Returns the k-th smallest value (0-based) of {@code values}. The input array
     * is NOT modified (a copy is partitioned).
     */
    public double select(double[] values, int k) {
        validate(values, k);
        return selectInPlace(values.clone(), k);
    }

    /** Same as {@link #select} but partitions {@code a} in place (it is reordered). */
    public double selectInPlace(double[] a, int k) {
        validate(a, k);
        int lo = 0, hi = a.length - 1;
        while (true) {
            if (lo == hi) return a[lo];
            double pivot = a[lo + rng.nextInt(hi - lo + 1)];
            // 3-way partition of a[lo..hi] around pivot
            int lt = lo, i = lo, gt = hi;
            while (i <= gt) {
                comparisons++;
                if (a[i] < pivot) swap(a, lt++, i++);
                else if (a[i] > pivot) { comparisons++; swap(a, i, gt--); }
                else { comparisons++; i++; }
            }
            if (k < lt) hi = lt - 1;
            else if (k > gt) lo = gt + 1;
            else return pivot;
        }
    }

    /** k-th largest (0-based): kthLargest(a, 0) is the maximum. */
    public double kthLargest(double[] values, int k) {
        validate(values, k);
        return select(values, values.length - 1 - k);
    }

    /** Nearest-rank percentile, q in [0,100]: the value at rank ceil(q/100 * n) (1-based). */
    public double percentile(double[] values, double q) {
        if (values == null || values.length == 0) throw new IllegalArgumentException("empty input");
        if (!(q >= 0 && q <= 100)) throw new IllegalArgumentException("percentile must be in [0,100]");
        int rank = (int) Math.ceil(q / 100.0 * values.length);
        int k = Math.max(0, rank - 1);
        return select(values, k);
    }

    static void validate(double[] a, int k) {
        if (a == null || a.length == 0) throw new IllegalArgumentException("cannot select from an empty array");
        if (k < 0 || k >= a.length) throw new IllegalArgumentException("k=" + k + " out of range [0," + (a.length - 1) + "]");
        for (double v : a) if (Double.isNaN(v)) throw new IllegalArgumentException("NaN values are not orderable");
    }

    private static void swap(double[] a, int i, int j) {
        double t = a[i]; a[i] = a[j]; a[j] = t;
    }

    public long comparisons() { return comparisons; }
    public void resetCounters() { comparisons = 0; }
}
