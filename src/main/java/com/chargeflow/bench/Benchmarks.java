package com.chargeflow.bench;

import com.chargeflow.algo.bloom.BloomFilter;
import com.chargeflow.algo.flow.EdmondsKarp;
import com.chargeflow.algo.flow.FordFulkersonDfs;
import com.chargeflow.algo.select.NaiveQuickselect;
import com.chargeflow.algo.select.RandomizedQuickselect;
import com.chargeflow.data.SyntheticData;
import com.chargeflow.domain.AllocationOptions;
import com.chargeflow.domain.HubConfig;
import com.chargeflow.domain.Vehicle;
import com.chargeflow.domain.VehicleRequest;
import com.chargeflow.engine.AllocationEngine;
import com.chargeflow.engine.PriorityTiering;
import com.chargeflow.engine.SlotAllocation;
import com.chargeflow.service.ValidationService;

import java.util.*;

/**
 * Reproducible micro-benchmarks. Each measurement does warm-up runs, then reports
 * the median of {@code reps} timed runs, together with machine-independent work
 * counters (arc scans, comparisons) so results can be compared across machines.
 * All inputs come from fixed seeds.
 */
public final class Benchmarks {

    private Benchmarks() { }

    public record Row(String experiment, String variant, long n, double medianMs, double work, double quality, String note) { }

    // ------------------------------------------------------------------ max-flow

    /**
     * Vehicle count n, hub scaled to n/250 x default (so congestion stays similar).
     * quality = charging slots served.
     */
    public static List<Row> flow(int[] sizes, int reps) {
        List<Row> rows = new ArrayList<>();
        ValidationService vs = new ValidationService();
        for (int n : sizes) {
            int factor = Math.max(1, n / 250);
            HubConfig hub = SyntheticData.scaledHub(factor);
            List<String> reg = SyntheticData.plates(Math.max(10, n / 2), new Random(11), Set.of());
            List<Vehicle> vehicles = SyntheticData.vehicles(n, 1000 + n, 0.4, reg, hub);
            List<VehicleRequest> reqs = vs.validate(hub, vehicles, AllocationOptions.defaults()).requests();
            List<List<Integer>> tiers = PriorityTiering.tier(reqs, i -> false, 0.2, 42).tiers();

            rows.add(time("max-flow", "Edmonds-Karp (BFS)", n, reps,
                    () -> AllocationEngine.maxFlow(hub, reqs, tiers, new EdmondsKarp()), hub));
            rows.add(time("max-flow", "Ford-Fulkerson (DFS)", n, reps,
                    () -> AllocationEngine.maxFlow(hub, reqs, tiers, new FordFulkersonDfs()), hub));
            rows.add(time("max-flow", "Greedy FCFS (baseline)", n, reps,
                    () -> AllocationEngine.greedy(hub, reqs, tiers), hub));
        }
        return rows;
    }

    /**
     * Where Edmonds-Karp's guarantee matters: random sparse directed graphs with long
     * s-t paths and capacities up to 10,000. DFS Ford-Fulkerson's augmentation count is
     * bounded only by |f*|, while Edmonds-Karp's is O(VE) whatever the capacities.
     * quality = max-flow value (must be equal), note = augmenting paths.
     */
    public static List<Row> flowDeepRandom(int[] nodeCounts, int reps) {
        List<Row> rows = new ArrayList<>();
        for (int n : nodeCounts) {
            java.util.function.Supplier<com.chargeflow.algo.flow.FlowNetwork> build = () -> {
                Random r = new Random(n);
                com.chargeflow.algo.flow.FlowNetwork g = new com.chargeflow.algo.flow.FlowNetwork(n);
                // a "corridor" layout: edges mostly go to near neighbours, so s-t paths are long
                for (int u = 0; u < n; u++)
                    for (int j = 0; j < 4; j++) {
                        int v = Math.floorMod(u + 1 + r.nextInt(6) - (j == 3 ? 4 : 0), n);
                        if (v != u) g.addEdge(u, v, 1 + r.nextInt(10_000));
                    }
                return g;
            };
            for (String variant : new String[]{"Edmonds-Karp (BFS)", "Ford-Fulkerson (DFS)"}) {
                double[] ms = new double[reps];
                long value = 0, paths = 0, scans = 0;
                for (int w = -1; w < reps; w++) {
                    com.chargeflow.algo.flow.FlowNetwork g = build.get();
                    com.chargeflow.algo.flow.MaxFlowSolver s = variant.startsWith("Edmonds") ? new EdmondsKarp() : new FordFulkersonDfs();
                    long t = System.nanoTime();
                    value = s.augment(g, 0, n / 2);
                    if (w >= 0) ms[w] = (System.nanoTime() - t) / 1e6;
                    paths = s.augmentingPaths();
                    scans = s.arcScans();
                }
                rows.add(new Row("max-flow (deep random)", variant, n, median(ms), scans, value, "paths=" + paths + " E~" + 4 * n));
            }
        }
        return rows;
    }

    private static Row time(String exp, String variant, int n, int reps, java.util.function.Supplier<SlotAllocation> f, HubConfig hub) {
        SlotAllocation last = null;
        for (int w = 0; w < 2; w++) last = f.get();
        double[] ms = new double[reps];
        for (int r = 0; r < reps; r++) {
            long t = System.nanoTime();
            last = f.get();
            ms[r] = (System.nanoTime() - t) / 1e6;
        }
        String note = last.nodes() == 0 ? "slot probes" : ("V=" + last.nodes() + " E=" + last.edges() + " paths=" + last.augmentingPaths());
        return new Row(exp, variant, n, median(ms), last.arcScans(), last.totalServed(), note + " chargers=" + hub.chargers().size());
    }

    // ------------------------------------------------------------------ selection

    /** Median selection on random and on sorted input. work = comparisons. */
    public static List<Row> selection(int[] randomSizes, int[] sortedSizes, int reps) {
        List<Row> rows = new ArrayList<>();
        for (int n : randomSizes) rows.addAll(selectionAt(n, false, reps));
        for (int n : sortedSizes) rows.addAll(selectionAt(n, true, reps));
        return rows;
    }

    private static List<Row> selectionAt(int n, boolean sorted, int reps) {
        Random r = new Random(n);
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = sorted ? i : r.nextDouble() * 200;
        int k = n / 2;
        String exp = sorted ? "selection (sorted input)" : "selection (random input)";
        List<Row> rows = new ArrayList<>();

        double expected;
        { double[] c = a.clone(); Arrays.sort(c); expected = c[k]; }

        double[] t1 = new double[reps]; long cmp1 = 0; double v1 = 0;
        for (int w = -2; w < reps; w++) {
            RandomizedQuickselect q = new RandomizedQuickselect(7 + w);
            long t = System.nanoTime(); v1 = q.select(a, k); if (w >= 0) { t1[w] = (System.nanoTime() - t) / 1e6; cmp1 = q.comparisons(); }
        }
        rows.add(new Row(exp, "Randomized Quickselect", n, median(t1), cmp1, v1 == expected ? 1 : 0, "k=n/2"));

        double[] t2 = new double[reps];
        for (int w = -2; w < reps; w++) {
            long t = System.nanoTime(); double[] c = a.clone(); Arrays.sort(c); double v = c[k];
            if (w >= 0) t2[w] = (System.nanoTime() - t) / 1e6;
            if (v != expected) throw new IllegalStateException();
        }
        rows.add(new Row(exp, "Sort then index (baseline)", n, median(t2), n * (Math.log(n) / Math.log(2)), 1, "Arrays.sort dual-pivot quicksort"));

        double[] t3 = new double[reps]; long cmp3 = 0; double v3 = 0;
        for (int w = -2; w < reps; w++) {
            NaiveQuickselect q = new NaiveQuickselect();
            long t = System.nanoTime(); v3 = q.select(a, k); if (w >= 0) { t3[w] = (System.nanoTime() - t) / 1e6; cmp3 = q.comparisons(); }
        }
        rows.add(new Row(exp, "First-pivot Quickselect (baseline)", n, median(t3), cmp3, v3 == expected ? 1 : 0, "deterministic pivot"));
        return rows;
    }

    // ------------------------------------------------------------------ bloom

    /**
     * Registry of n plates at 1% target FPR. Measures build time, query time for 100k
     * absent plates, empirical FPR and memory against a HashSet of the same strings.
     */
    public static List<Row> bloom(int[] sizes, double fpr, int probes) {
        List<Row> rows = new ArrayList<>();
        warmUpBloom(fpr);
        for (int n : sizes) {
            List<String> reg = SyntheticData.plates(n, new Random(n), Set.of());
            Set<String> regSet = new HashSet<>(reg);
            List<String> absent = SyntheticData.plates(probes, new Random(n + 99), regSet);

            // 2 untimed warm-up passes, then 5 timed passes (median reported), same as the other experiments
            BloomFilter bf = null;
            double[] build = new double[5], query = new double[5], hq = new double[5];
            int fp = 0, hits = 0;
            for (int r = -2; r < 5; r++) {
                long t = System.nanoTime();
                bf = buildFilter(reg, n, fpr);
                if (r >= 0) build[r] = (System.nanoTime() - t) / 1e6;
                t = System.nanoTime();
                fp = countBloomHits(bf, absent);
                if (r >= 0) query[r] = (System.nanoTime() - t) / 1e6;
                t = System.nanoTime();
                hits = countSetHits(regSet, absent);
                if (r >= 0) hq[r] = (System.nanoTime() - t) / 1e6;
            }
            boolean noFalseNeg = true;
            for (String p : reg) if (!bf.mightContain(p)) { noFalseNeg = false; break; }
            double empirical = (double) fp / probes;
            double theory = BloomFilter.theoreticalFpr(n, bf.bitCount(), bf.hashCount());
            rows.add(new Row("bloom", "Bloom build", n, median(build), bf.bitCount(), bf.hashCount(), "m bits / k hashes"));
            rows.add(new Row("bloom", "Bloom query (absent keys)", n, median(query), probes, empirical,
                    String.format(Locale.ROOT, "empirical FPR=%.5f theoretical=%.5f falseNegatives=%s", empirical, theory, noFalseNeg ? "0" : "FOUND")));
            rows.add(new Row("bloom", "HashSet query (baseline)", n, median(hq), probes, hits, "exact, 0 false positives"));
            rows.add(new Row("bloom", "Memory Bloom (bytes)", n, 0, bf.memoryBytes(), 0, ""));
            rows.add(new Row("bloom", "Memory HashSet est. (bytes)", n, 0, n * 96.0, 0, "~96 B per 10-char plate entry"));
        }
        return rows;
    }

    /** Empirical vs theoretical FPR for several target rates at fixed n. */
    public static List<Row> bloomFprSweep(int n, double[] targets, int probes) {
        List<Row> rows = new ArrayList<>();
        List<String> reg = SyntheticData.plates(n, new Random(5), Set.of());
        List<String> absent = SyntheticData.plates(probes, new Random(6), new HashSet<>(reg));
        for (double p : targets) {
            BloomFilter bf = BloomFilter.optimal(n, p);
            for (String s : reg) bf.add(s);
            int fp = 0;
            for (String s : absent) if (bf.mightContain(s)) fp++;
            rows.add(new Row("bloom-fpr", "target " + p, n, 0, BloomFilter.theoreticalFpr(n, bf.bitCount(), bf.hashCount()),
                    (double) fp / probes, "m=" + bf.bitCount() + " k=" + bf.hashCount() + " bits/key=" + String.format(Locale.ROOT, "%.2f", (double) bf.bitCount() / n)));
        }
        return rows;
    }

    // Timed bodies live in small methods so the JIT compiles them once and every size is measured on compiled code.
    private static BloomFilter buildFilter(List<String> keys, int n, double fpr) {
        BloomFilter bf = BloomFilter.optimal(n, fpr);
        for (String k : keys) bf.add(k);
        return bf;
    }

    private static int countBloomHits(BloomFilter bf, List<String> probes) {
        int c = 0;
        for (String p : probes) if (bf.mightContain(p)) c++;
        return c;
    }

    private static int countSetHits(Set<String> set, List<String> probes) {
        int c = 0;
        for (String p : probes) if (set.contains(p)) c++;
        return c;
    }

    private static volatile int sink;

    /** Untimed run so that the first measured size pays neither for JIT compilation nor for CPU frequency ramp-up. */
    private static void warmUpBloom(double fpr) {
        List<String> keys = SyntheticData.plates(50_000, new Random(1), Set.of());
        Set<String> set = new HashSet<>(keys);
        List<String> probes = SyntheticData.plates(50_000, new Random(2), set);
        // at least 8 passes and at least ~400 ms, so the JIT has compiled the code and the CPU has left its
        // low-power state (Apple Silicon starts short bursts on efficiency cores) before anything is timed
        long until = System.nanoTime() + 400_000_000L;
        for (int i = 0; i < 8 || System.nanoTime() < until; i++) {
            BloomFilter bf = buildFilter(keys, keys.size(), fpr);
            sink += countBloomHits(bf, probes) + countSetHits(set, probes);
        }
    }

    static double median(double[] a) {
        double[] c = a.clone();
        Arrays.sort(c);
        int n = c.length;
        double m = n % 2 == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2;
        return Math.round(m * 1000) / 1000.0;
    }
}
