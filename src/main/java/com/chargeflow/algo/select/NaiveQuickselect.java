package com.chargeflow.algo.select;

/**
 * Baseline: deterministic Quickselect with a fixed first-element pivot and a
 * Lomuto two-way partition. Expected O(n) on random data, but Θ(n^2) on sorted,
 * reverse-sorted or all-equal input, which is exactly what a ranked priority list
 * often looks like. Used only to show why randomisation matters.
 */
public final class NaiveQuickselect {

    private long comparisons;

    public double select(double[] values, int k) {
        RandomizedQuickselect.validate(values, k);
        double[] a = values.clone();
        int lo = 0, hi = a.length - 1;
        while (lo < hi) {
            double pivot = a[lo];
            int store = lo;
            for (int i = lo + 1; i <= hi; i++) {
                comparisons++;
                if (a[i] < pivot) { store++; double t = a[store]; a[store] = a[i]; a[i] = t; }
            }
            double t = a[lo]; a[lo] = a[store]; a[store] = t;
            if (k == store) return a[store];
            if (k < store) hi = store - 1; else lo = store + 1;
        }
        return a[lo];
    }

    public long comparisons() { return comparisons; }
}
