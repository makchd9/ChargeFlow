package com.chargeflow.engine;

import com.chargeflow.algo.select.RandomizedQuickselect;
import com.chargeflow.domain.VehicleRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Splits vehicles into a protected priority tier and the rest.
 *
 * <p>Priority score (higher is more urgent):</p>
 * <pre>
 *   score = categoryWeight            (EMERGENCY 100, FLEET 40, PRIVATE 0)
 *         + 0.5 * (100 - batteryPct)  (low battery first)
 *         + 30  * min(1, slotsNeeded / windowSlots)   (tight deadlines first)
 *         + 10  if a registered subscriber (verified against the registry)
 * </pre>
 *
 * <p>The tier boundary is the k-th largest score, k = ceil(fraction * feasible),
 * found by <b>randomized Quickselect</b> in expected O(n) instead of sorting all
 * scores in O(n log n). Ties at the threshold are broken by input order, so
 * exactly k vehicles enter tier 1 and the result is deterministic for a fixed input.</p>
 */
public final class PriorityTiering {

    public record Result(double[] scores, List<List<Integer>> tiers, int k, double threshold,
                         double p50, double p90, long comparisons) { }

    private PriorityTiering() { }

    public static double score(VehicleRequest r, boolean registered) {
        double urgency = r.windowSlots() == 0 ? 1.0 : Math.min(1.0, (double) r.slotsNeeded() / r.windowSlots());
        return r.vehicle().category().weight()
                + 0.5 * (100 - r.vehicle().batteryPct())
                + 30 * urgency
                + (registered ? 10 : 0);
    }

    public static Result tier(List<VehicleRequest> reqs, IntPredicate registered, double fraction, long seed) {
        int n = reqs.size();
        double[] scores = new double[n];
        List<Integer> feasible = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            scores[i] = Math.round(score(reqs.get(i), registered.test(i)) * 100) / 100.0;
            if (reqs.get(i).feasible()) feasible.add(i);
        }
        RandomizedQuickselect qs = new RandomizedQuickselect(seed);
        double p50 = Double.NaN, p90 = Double.NaN;
        if (!feasible.isEmpty()) {
            double[] fs = new double[feasible.size()];
            for (int j = 0; j < fs.length; j++) fs[j] = scores[feasible.get(j)];
            p50 = qs.percentile(fs, 50);
            p90 = qs.percentile(fs, 90);
        }

        int k = (int) Math.ceil(fraction * feasible.size());
        List<List<Integer>> tiers = new ArrayList<>();
        if (k <= 0 || k >= feasible.size()) {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < n; i++) all.add(i);
            tiers.add(all);
            return new Result(scores, tiers, k <= 0 ? 0 : feasible.size(), Double.NaN, p50, p90, qs.comparisons());
        }
        double[] fs = new double[feasible.size()];
        for (int j = 0; j < fs.length; j++) fs[j] = scores[feasible.get(j)];
        double threshold = qs.kthLargest(fs, k - 1);   // the k-th largest score

        int above = 0;
        for (double s : fs) if (s > threshold) above++;
        int tiesAllowed = k - above;                    // how many at == threshold may enter tier 1
        List<Integer> t1 = new ArrayList<>(), t2 = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!reqs.get(i).feasible()) { t2.add(i); continue; }
            if (scores[i] > threshold) t1.add(i);
            else if (scores[i] == threshold && tiesAllowed > 0) { t1.add(i); tiesAllowed--; }
            else t2.add(i);
        }
        tiers.add(t1);
        tiers.add(t2);
        return new Result(scores, tiers, k, threshold, p50, p90, qs.comparisons());
    }
}
